package press.tenoridge.orders;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    JwtDecoder jwtDecoder(@Value("${ridge.jwt.secret}") String secret) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        org.springframework.security.oauth2.core.OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> claims = jwt -> {
            Object tenant = jwt.getClaims().get("tenant_id");
            if (jwt.getAudience() != null && jwt.getAudience().contains("ridge-orders") && tenant instanceof String value
                    && value.matches("[A-Za-z0-9-]{1,64}")) {
                return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
            }
            return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                new org.springframework.security.oauth2.core.OAuth2Error("invalid_token", "Invalid audience or tenant", null));
        };
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer("ridge-local"), claims));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/**").hasAuthority("SCOPE_ops")
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/**").hasAuthority("SCOPE_orders.write")
                .anyRequest().hasAuthority("SCOPE_orders.read"))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) -> ApiErrors.write(response, 401))
                .accessDeniedHandler((request, response, error) -> ApiErrors.write(response, 403)))
            .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                .authenticationEntryPoint((request, response, error) -> ApiErrors.write(response, 401))
                .accessDeniedHandler((request, response, error) -> ApiErrors.write(response, 403))).build();
    }
}
