package com.tradecore.foundation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

final class ApiRateLimitFilter extends OncePerRequestFilter {
    private static final Pattern CANCEL_PATH = Pattern.compile("^/api/v1/orders/[0-9a-fA-F-]{36}/cancel$");
    private final ApiRateLimiter limiter;

    ApiRateLimitFilter(ApiRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String endpoint = endpoint(request);
        if (endpoint == null) {
            chain.doFilter(request, response);
            return;
        }
        String identity = clientIdentity(request, endpoint);
        if (!limiter.allow(endpoint, identity)) {
            response.setHeader("Retry-After", Long.toString(limiter.retryAfterSeconds(endpoint)));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            return;
        }
        chain.doFilter(request, response);
    }

    private static String endpoint(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return null;
        String path = request.getRequestURI();
        if ("/api/v1/auth/register".equals(path)) return "register";
        if ("/api/v1/orders".equals(path)) return "order";
        if (CANCEL_PATH.matcher(path).matches()) return "cancel";
        return null;
    }

    private static String clientIdentity(HttpServletRequest request, String endpoint) {
        if (!"register".equals(endpoint)) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken)) {
                return "user:" + authentication.getName();
            }
        }
        // Do not trust client-supplied forwarding headers; remoteAddr is set by the servlet container.
        return "ip:" + request.getRemoteAddr();
    }
}
