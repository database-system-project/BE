package com.databasesystem.hospital.config;

import com.databasesystem.hospital.auth.AccountJwtConverter;
import com.databasesystem.hospital.auth.TokenService;
import com.databasesystem.hospital.common.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccountJwtConverter converter,
                                            ObjectMapper mapper, UrlBasedCorsConfigurationSource corsConfigurationSource) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            mapper.writeValue(response.getOutputStream(),
                                    new ApiError("UNAUTHORIZED", "로그인이 필요하거나 인증 정보가 만료되었습니다.", null));
                        }))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            mapper.writeValue(response.getOutputStream(),
                                    new ApiError("UNAUTHORIZED", "로그인이 필요하거나 인증 정보가 만료되었습니다.", null));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            mapper.writeValue(response.getOutputStream(),
                                    new ApiError("FORBIDDEN", "이 작업을 수행할 권한이 없습니다.", null));
                        }))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() { return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }

    @Bean
    SecretKey jwtSecret(@Value("${hospital.security.jwt-secret}") String value) {
        byte[] secret = value.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) { throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes"); }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecret) { return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecret)); }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecret, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecret).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator time = new JwtTimestampValidator(Duration.ZERO);
        time.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(time, new JwtIssuerValidator(TokenService.ISSUER)));
        return decoder;
    }
}
