package io.github.tahausama210.driftwatch.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

class BearerTokenAuthenticationTest {

    private static final String ENROLLMENT_TOKEN = "enrollment-secret-0123456789abcdef";

    private final BearerTokenAuthenticationConverter converter = new BearerTokenAuthenticationConverter();
    private final EnrollmentTokenAuthenticationProvider enrollment =
            new EnrollmentTokenAuthenticationProvider(ENROLLMENT_TOKEN);

    @ParameterizedTest
    @ValueSource(strings = {"Bearer abc", "bearer abc", "BEARER   abc  "})
    void theBearerSchemeIsCaseInsensitiveAndTheTokenTrimmed(String header) {
        assertThat(converter.convert(requestWith(header)).getCredentials()).isEqualTo("abc");
    }

    @Test
    void noAuthorizationHeaderMeansAnonymous() {
        assertThat(converter.convert(new MockHttpServletRequest())).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic dXNlcjpwYXNz", "Bearer", "Bearer ", "Bearer two tokens", "Token abc"})
    void malformedHeadersAreRejected(String header) {
        assertThatThrownBy(() -> converter.convert(requestWith(header))).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void theTokenIsNeverExposedAsThePrincipal() {
        BearerTokenAuthenticationRequest request = new BearerTokenAuthenticationRequest("dwa_secret");

        assertThat(request.getPrincipal()).isNotEqualTo("dwa_secret");
        assertThat(request.toString()).doesNotContain("dwa_secret");
    }

    @Test
    void theEnrollmentSecretGrantsTheEnrollAuthority() {
        Authentication result = enrollment.authenticate(new BearerTokenAuthenticationRequest(ENROLLMENT_TOKEN));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getCredentials()).isNull();
        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(EnrollmentTokenAuthenticationProvider.AUTHORITY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "enrollment-secret", ENROLLMENT_TOKEN + " ", "ENROLLMENT-SECRET-0123456789ABCDEF"})
    void anythingButTheExactSecretIsRejected(String presented) {
        assertThatThrownBy(() -> enrollment.authenticate(new BearerTokenAuthenticationRequest(presented)))
                .isInstanceOf(BadCredentialsException.class);
    }

    private static MockHttpServletRequest requestWith(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        return request;
    }
}
