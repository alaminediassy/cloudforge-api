package io.cloudforge.shared.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudforge.identity.api.security.JwtAuthenticationFilter;
import io.cloudforge.identity.api.security.SecurityErrorHandler;
import io.cloudforge.identity.application.usecase.AuthenticateAccessTokenUseCase;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfiguration {
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityErrorHandler securityErrorHandler(ObjectMapper mapper) {
        return new SecurityErrorHandler(mapper);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticateAccessTokenUseCase authenticate,
                                           SecurityErrorHandler errors) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/identity/register", "/api/v1/identity/login",
                                "/api/v1/identity/verify-email", "/api/v1/identity/resend-verification-code",
                                "/api/v1/identity/refresh", "/api/v1/identity/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().authenticated())
                // Not a servlet Filter bean: register exactly once, inside Spring Security.
                .addFilterBefore(new JwtAuthenticationFilter(authenticate, errors), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
