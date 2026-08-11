package com.payguard.reconciliation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates internal service-to-service calls with a shared token.
 *
 * <p>Mirrors the fraud-engine and user-service filters. Reconciliation previously exposed
 * {@code /internal/**} with {@code permitAll()}, so anyone with network reach could trigger a
 * settlement run.
 */
public class InternalServiceTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-PayGuard-Internal-Token";
    public static final String PRINCIPAL = "internal-service";
    public static final String ROLE = "ROLE_INTERNAL_SERVICE";

    private final byte[] expectedToken;

    public InternalServiceTokenFilter(String expectedToken) {
        this.expectedToken = StringUtils.hasText(expectedToken)
                ? expectedToken.getBytes(StandardCharsets.UTF_8)
                : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expectedToken != null && matches(request.getHeader(HEADER))) {
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(
                            PRINCIPAL, null, List.of(new SimpleGrantedAuthority(ROLE))));
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String provided) {
        if (!StringUtils.hasText(provided)) {
            return false;
        }
        return MessageDigest.isEqual(expectedToken, provided.getBytes(StandardCharsets.UTF_8));
    }
}
