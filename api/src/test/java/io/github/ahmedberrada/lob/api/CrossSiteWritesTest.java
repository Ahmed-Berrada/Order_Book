package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;

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

    @ParameterizedTest
    @CsvSource({
        // method, Content-Type, protected (no Fetch Metadata, no Origin: an old browser or a machine)
        "POST, application/x-www-form-urlencoded, true",
        "POST, 'multipart/form-data; boundary=x', true",
        "POST, 'Text/Plain;charset=UTF-8', true",
        "PUT, ' text/plain ', true",
        "POST, application/json, false",
        "POST, 'application/json; charset=UTF-8', false",
        "DELETE, '', false",
        "GET, application/x-www-form-urlencoded, false",
    })
    @Rulebook("AC-008")
    void protectsEveryWriteWithABodyAFormCanSend(String method, String contentType, boolean expected) {
        MockHttpServletRequest request = request(method, "", "");
        if (!contentType.isEmpty()) {
            request.setContentType(contentType);
        }
        assertThat(CrossSiteWrites.isCrossSiteWrite(request)).isEqualTo(expected);
    }

    @Test
    void tokensAreNeverStoredSoNoneCanBePresentedAndNoSessionIsCreated() {
        MockHttpServletRequest request = request("POST", "cross-site", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsrfToken token = CrossSiteWrites.NO_TOKENS.generateToken(request);
        assertThat(token.getToken()).isNotBlank()
                .isNotEqualTo(CrossSiteWrites.NO_TOKENS.generateToken(request).getToken());
        CrossSiteWrites.NO_TOKENS.saveToken(token, request, response);
        assertThat(CrossSiteWrites.NO_TOKENS.loadToken(request)).isNull();
        assertThat(request.getSession(false)).isNull();
        assertThat(response.getCookies()).isEmpty();
    }
}
