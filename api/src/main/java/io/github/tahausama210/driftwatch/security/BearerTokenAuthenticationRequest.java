package io.github.tahausama210.driftwatch.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** An unauthenticated bearer token extracted from the {@code Authorization} header. */
final class BearerTokenAuthenticationRequest extends AbstractAuthenticationToken {

    private final String token;

    BearerTokenAuthenticationRequest(String token) {
        super(List.of());
        this.token = token;
        setAuthenticated(false);
    }

    @Override
    public String getCredentials() {
        return token;
    }

    /** Deliberately not the token, which {@code toString()} and audit events would otherwise expose. */
    @Override
    public String getPrincipal() {
        return "bearer-token";
    }
}
