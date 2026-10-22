package press.tenoridge.orders;

import java.io.IOException;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    static String code(int status) {
        return switch (status) {
            case 400 -> "invalid_request";
            case 401 -> "unauthenticated";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 406 -> "not_acceptable";
            case 415 -> "unsupported_media_type";
            case 409 -> "conflict";
            case 502 -> "upstream_failure";
            case 503 -> "unavailable";
            case 504 -> "upstream_timeout";
            default -> "internal_error";
        };
    }

    static void write(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write("{\"code\":\"" + code(status) + "\",\"requestId\":\"" + MDC.get("requestId") + "\"}");
    }

    private ResponseEntity<Map<String, String>> response(int status) {
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(Map.of("code", code(status), "requestId", MDC.get("requestId")));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException failure) {
        return response(failure.getStatusCode().value());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalid(Exception failure) {
        return response(400);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException failure) {
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).warn("requestId={} integrity violation", MDC.get("requestId"), failure);
        Throwable cause = failure.getMostSpecificCause();
        if (!(cause instanceof java.sql.SQLException sql) || !"23505".equals(sql.getSQLState())) return response(500);
        return response(409);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception failure) {
        if (failure instanceof org.springframework.web.ErrorResponse error) {
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("code", code(error.getStatusCode().value()), "requestId", MDC.get("requestId")));
        }
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("requestId={} unhandled request failure", MDC.get("requestId"), failure);
        return response(500);
    }
}
