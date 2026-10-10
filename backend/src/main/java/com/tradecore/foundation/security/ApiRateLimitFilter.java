package com.tradecore.foundation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

final class ApiRateLimitFilter extends OncePerRequestFilter {
    private static final Pattern CANCEL_PATH = Pattern.compile("^/api/v1/orders/[0-9a-fA-F-]{36}/cancel$");
    private static final Pattern NUMERIC_IP = Pattern.compile("[0-9a-fA-F:.]+");
    private final ApiRateLimiter limiter;
    private final List<Cidr> trustedProxies;

    ApiRateLimitFilter(ApiRateLimiter limiter, List<String> trustedProxyCidrs) {
        this.limiter = limiter;
        this.trustedProxies = trustedProxyCidrs.stream().filter(value -> value != null && !value.isBlank())
                .map(Cidr::parse).toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String endpoint = endpoint(request);
        if (endpoint == null) {
            chain.doFilter(request, response);
            return;
        }
        String identity = clientIdentity(request, endpoint, trustedProxies);
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

    private static String clientIdentity(HttpServletRequest request, String endpoint, List<Cidr> trustedProxies) {
        if (!"register".equals(endpoint)) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken)) {
                return "user:" + authentication.getName();
            }
        }
        String remoteAddress = request.getRemoteAddr();
        if (trustedProxies.stream().noneMatch(cidr -> cidr.contains(remoteAddress))) {
            return "ip:" + remoteAddress;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null) {
            String firstAddress = forwardedFor.split(",", 2)[0].trim();
            if (isIpAddress(firstAddress)) return "ip:" + firstAddress;
        }
        return "ip:" + remoteAddress;
    }

    private static boolean isIpAddress(String value) {
        if (value == null || !NUMERIC_IP.matcher(value).matches()) return false;
        try {
            InetAddress.getByName(value);
            return true;
        } catch (UnknownHostException invalidAddress) {
            return false;
        }
    }

    private record Cidr(byte[] network, int prefixLength) {
        static Cidr parse(String value) {
            String[] parts = value.trim().split("/", -1);
            if (parts.length > 2 || !isIpAddress(parts[0])) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR");
            }
            try {
                byte[] address = InetAddress.getByName(parts[0]).getAddress();
                int maxPrefix = address.length * 8;
                int prefix = parts.length == 1 ? maxPrefix : Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > maxPrefix) throw new IllegalArgumentException("Invalid trusted proxy CIDR");
                return new Cidr(address, prefix);
            } catch (UnknownHostException | NumberFormatException invalidCidr) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR", invalidCidr);
            }
        }

        boolean contains(String value) {
            if (!isIpAddress(value)) return false;
            try {
                byte[] address = InetAddress.getByName(value).getAddress();
                if (address.length != network.length) return false;
                int wholeBytes = prefixLength / 8;
                int remainingBits = prefixLength % 8;
                for (int index = 0; index < wholeBytes; index++) {
                    if (address[index] != network[index]) return false;
                }
                if (remainingBits == 0) return true;
                int mask = 0xff << (8 - remainingBits);
                return (address[wholeBytes] & mask) == (network[wholeBytes] & mask);
            } catch (UnknownHostException invalidAddress) {
                return false;
            }
        }
    }
}
