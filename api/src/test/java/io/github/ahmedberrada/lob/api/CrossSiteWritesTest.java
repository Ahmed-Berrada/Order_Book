package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

class CrossSiteWritesTest {

    private static MockHttpServletRequest request(String method, String site, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/instruments/AAPL/orders");
        if (!site.isEmpty()) {
            request.addHeader("Sec-Fetch-Site", site);
        }
        if (!origin.isEmpty()) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    @ParameterizedTest
    @CsvSource({
        // method, Sec-Fetch-Site, Origin, protected
        "POST, cross-site, '', true",
        "POST, same-site, '', true",
        "POST, unknown-value, '', true",
        "POST, same-origin, '', false",
        "POST, none, '', false",
        "POST, '', https://evil.example, true",
        "POST, '', '', false",
        "PUT, cross-site, '', true",
        "DELETE, cross-site, '', true",
        "PATCH, cross-site, '', true",
        "delete, '', https://evil.example, true",
        "GET, cross-site, https://evil.example, false",
        "HEAD, cross-site, '', false",
        "OPTIONS, cross-site, '', false",
        "TRACE, cross-site, '', false",
        "POST, same-origin, https://evil.example, false",
    })
    @Rulebook("AC-008")
    void protectsExactlyTheWritesABrowserMarksAsCrossSite(String method, String site, String origin,
            boolean expected) {
        assertThat(CrossSiteWrites.isCrossSiteWrite(request(method, site, origin))).isEqualTo(expected);
        assertThat(CrossSiteWrites.MATCHER.matches(request(method, site, origin))).isEqualTo(expected);
    }
}
