package press.tenoridge.orders;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-Id");
        String requestId = supplied != null && supplied.matches("[A-Za-z0-9-]{1,64}")
            ? supplied : UUID.randomUUID().toString();
        long started = System.nanoTime();
        response.setHeader("X-Request-Id", requestId);
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            LOGGER.info("requestId={} method={} status={} elapsedMs={}", requestId,
                request.getMethod(), response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            MDC.remove("requestId");
        }
    }
}
