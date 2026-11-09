package io.github.tahausama210.driftwatch.security;

import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/**
 * Authenticates an agent by the SHA-256 hash of its token.
 *
 * <p>The comparison happens inside the database's unique-index lookup rather than with a
 * constant-time comparison in Java. That is safe because the attacker controls the token, not its
 * hash: any timing difference the lookup leaks concerns digest prefixes, and learning those does not
 * help construct a token that hashes to a stored value.
 */
final class AgentTokenAuthenticationProvider implements AuthenticationProvider {

    static final String AUTHORITY = "agent:submit-scans";

    private final AgentCredentialRepository credentials;

    AgentTokenAuthenticationProvider(AgentCredentialRepository credentials) {
        this.credentials = credentials;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String token = ((BearerTokenAuthenticationRequest) authentication).getCredentials();
        if (!AgentTokens.hasValidFormat(token)) {
            throw new BadCredentialsException("Not an agent token");
        }
        String tokenHash = AgentTokens.hash(token);
        UUID hostId;
        try {
            hostId = credentials
                    .findHostIdByTokenHash(tokenHash)
                    .orElseThrow(() -> new BadCredentialsException("Unknown or revoked agent token"));
        } catch (DataAccessException e) {
            throw new AuthenticationServiceException("Agent token lookup failed", e);
        }
        return new PreAuthenticatedAuthenticationToken(
                new AgentPrincipal(hostId, tokenHash), null, AuthorityUtils.createAuthorityList(AUTHORITY));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationRequest.class.isAssignableFrom(authentication);
    }
}
