package com.tradecore.foundation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ApiRateLimitFilterTest {
    private final ApiRateLimiter limiter = mock(ApiRateLimiter.class);
    private final ApiRateLimitFilter filter = new ApiRateLimitFilter(limiter, List.of());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exceededLimitReturns429AndRetryAfter() throws Exception {
        when(limiter.allow("register", "ip:192.0.2.10")).thenReturn(false);
        when(limiter.retryAfterSeconds("register")).thenReturn(3600L);
        MockHttpServletRequest request = request("POST", "/api/v1/auth/register", "192.0.2.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
        assertThat(continued).isFalse();
    }

    @Test
    void trustedProxyMaySupplyOriginalClientAddress() throws Exception {
        ApiRateLimitFilter trustedFilter = new ApiRateLimitFilter(limiter, List.of("10.20.0.0/16"));
        when(limiter.allow("register", "ip:198.51.100.25")).thenReturn(true);
        MockHttpServletRequest request = request("POST", "/api/v1/auth/register", "10.20.1.7");
        request.addHeader("X-Forwarded-For", "198.51.100.25, 10.20.1.7");

        trustedFilter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        verify(limiter).allow("register", "ip:198.51.100.25");
    }

    @Test
    void untrustedRemoteAddressCannotChooseForwardedIdentity() throws Exception {
        when(limiter.allow("register", "ip:203.0.113.8")).thenReturn(true);
        MockHttpServletRequest request = request("POST", "/api/v1/auth/register", "203.0.113.8");
        request.addHeader("X-Forwarded-For", "198.51.100.25");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        verify(limiter).allow("register", "ip:203.0.113.8");
    }

    @Test
    void authenticatedOrderUsesUserIdentityAndOtherRoutesPassUnchanged() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("trader@example.invalid", "n/a", List.of()));
        when(limiter.allow("order", "user:trader@example.invalid")).thenReturn(true);
        MockHttpServletRequest order = request("POST", "/api/v1/orders", "192.0.2.10");
        MockHttpServletResponse orderResponse = new MockHttpServletResponse();
        filter.doFilter(order, orderResponse, (req, res) -> { });
        verify(limiter).allow("order", "user:trader@example.invalid");

        MockHttpServletRequest getOrders = request("GET", "/api/v1/orders", "192.0.2.10");
        MockHttpServletResponse getResponse = new MockHttpServletResponse();
        filter.doFilter(getOrders, getResponse, (req, res) -> getResponse.setStatus(202));
        assertThat(getResponse.getStatus()).isEqualTo(202);
    }

    @Test
    void cancellationPathIsLimitedAndUsesAuthenticatedIdentity() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("trader@example.invalid", "n/a", List.of()));
        String identity = "user:trader@example.invalid";
        when(limiter.allow("cancel", identity)).thenReturn(true);

        filter.doFilter(request("POST", "/api/v1/orders/1e3a1b0c-35c8-4ccb-8fac-ccf1843e5638/cancel", "192.0.2.10"),
                new MockHttpServletResponse(), (req, res) -> { });

        verify(limiter).allow("cancel", identity);
    }

    private static MockHttpServletRequest request(String method, String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        return request;
    }
}
