package io.github.tahausama210.driftwatch.security;

import java.security.MessageDigest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/**
 * Authenticates enrollment requests against the shared enrollment secret.
 *
 * <p>Both values are hashed before {@link MessageDigest#isEqual} compares them: {@code isEqual} is
 * constant-time only for inputs of equal length, and comparing fixed-length digests also keeps the
 * secret's length from leaking through timing.
 */
final class EnrollmentTokenAuthenticationProvider implements AuthenticationProvider {

    static final String AUTHORITY = "agent:enroll";
    static final String PRINCIPAL = "enrollment-client";

    private final byte[] expectedDigest;

    EnrollmentTokenAuthenticationProvider(String enrollmentToken) {
        this.expectedDigest = AgentTokens.sha256(enrollmentToken);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String presented = ((BearerTokenAuthenticationRequest) authentication).getCredentials();
        if (!MessageDigest.isEqual(AgentTokens.sha256(presented), expectedDigest)) {
            throw new BadCredentialsException("Invalid enrollment token");
        }
        return new PreAuthenticatedAuthenticationToken(PRINCIPAL, null, AuthorityUtils.createAuthorityList(AUTHORITY));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationRequest.class.isAssignableFrom(authentication);
    }
}
