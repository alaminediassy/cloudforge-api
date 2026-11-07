package io.cloudforge.identity.api.security;

import io.cloudforge.identity.application.usecase.AuthenticateAccessTokenUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Pattern BEARER = Pattern.compile("^Bearer +([^\\s,]+)$", Pattern.CASE_INSENSITIVE);
    private final AuthenticateAccessTokenUseCase authenticateAccessToken;
    private final SecurityErrorHandler errors;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> headers = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (headers.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        var matcher = BEARER.matcher(headers.getFirst());
        if (headers.size() != 1 || !matcher.matches()) {
            reject(request, response);
            return;
        }
        Optional<UUID> userId;
        try {
            userId = authenticateAccessToken.authenticate(matcher.group(1));
        } catch (DataAccessResourceFailureException | CannotCreateTransactionException ex) {
            SecurityContextHolder.clearContext();
            log.error("Authentication dependency unavailable: type={}", ex.getClass().getName());
            errors.unavailable(request, response);
            return;
        } catch (RuntimeException ex) {
            SecurityContextHolder.clearContext();
            log.error("Authentication failed unexpectedly: type={}", ex.getClass().getName());
            errors.unexpected(request, response);
            return;
        }
        if (userId.isEmpty()) {
            reject(request, response);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(userId.get()), null, List.of()));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        errors.unauthorized(request, response);
    }
}
