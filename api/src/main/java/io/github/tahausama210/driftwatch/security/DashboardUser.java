package io.github.tahausama210.driftwatch.security;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who made a dashboard request, as recorded in audit logs: never the token itself.
 *
 * @param subject the token's {@code sub}, Keycloak's stable user (or service account) id
 * @param username the token's {@code preferred_username}, for humans reading the log; absent when
 *     the client's scopes leave it out
 */
public record DashboardUser(String subject, @Nullable String username) {

    static final String PREFERRED_USERNAME_CLAIM = "preferred_username";

    public static DashboardUser of(Jwt jwt) {
        return new DashboardUser(jwt.getSubject(), jwt.getClaimAsString(PREFERRED_USERNAME_CLAIM));
    }
}
