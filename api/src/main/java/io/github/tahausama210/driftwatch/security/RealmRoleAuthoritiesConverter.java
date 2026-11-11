package io.github.tahausama210.driftwatch.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps Keycloak realm roles, carried in the {@code realm_access.roles} claim, to {@code ROLE_<name>}
 * authorities (e.g. {@code viewer} becomes {@code ROLE_viewer}).
 *
 * <p>The token's {@code scope} claim is deliberately ignored: Spring's default converter would turn
 * it into {@code SCOPE_*} authorities, but authorization here is by role only. A claim with an
 * unexpected shape yields no authorities rather than an error, so such a token is authenticated but
 * forbidden ({@code 403}), never a server error.
 */
final class RealmRoleAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String REALM_ACCESS_CLAIM = "realm_access";
    static final String ROLES = "roles";
    static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        if (!(jwt.getClaims().get(REALM_ACCESS_CLAIM) instanceof Map<?, ?> realmAccess)
                || !(realmAccess.get(ROLES) instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(role -> !role.isBlank())
                .distinct()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .toList();
    }
}
