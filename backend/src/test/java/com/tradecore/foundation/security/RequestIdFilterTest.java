package com.tradecore.foundation.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
    @Test
    void generatesShortRequestIdInHeaderAndMdcThenClearsIt() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/markets"), response,
                (request, result) -> assertThat(MDC.get(RequestIdFilter.MDC_KEY)).matches("[0-9a-f]{12}"));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches("[0-9a-f]{12}");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
