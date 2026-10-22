package press.tenoridge.orders;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;

class ApiErrorsTest {
    @Test void unexpectedIntegrityFailureIsServerErrorAndDoesNotLeakSql() {
        MDC.put("requestId", "integrity-test");
        try {
            var response = new ApiErrors().conflict(new DataIntegrityViolationException("private SQL detail",
                new java.sql.SQLException("private foreign key detail", "23503")));
            assertEquals(500, response.getStatusCode().value());
            assertEquals(java.util.Map.of("code", "internal_error", "requestId", "integrity-test"), response.getBody());
        } finally { MDC.remove("requestId"); }
    }
}
