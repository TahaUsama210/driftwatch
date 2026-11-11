package io.github.tahausama210.driftwatch.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class RealmRoleAuthoritiesConverterTest {

    private final RealmRoleAuthoritiesConverter converter = new RealmRoleAuthoritiesConverter();

    @Test
    void realmRolesBecomeRoleAuthorities() {
        Jwt jwt = token(Map.of("realm_access", Map.of("roles", List.of("viewer", "admin", "viewer"))));

        assertThat(authorities(jwt)).containsExactly("ROLE_viewer", "ROLE_admin");
    }

    @Test
    void scopesAndClientRolesGrantNothing() {
        Jwt jwt = token(Map.of(
                "scope",
                "profile email",
                "resource_access",
                Map.of("driftwatch-web", Map.of("roles", List.of("admin")))));

        assertThat(authorities(jwt)).isEmpty();
    }

    static Stream<Object> malformedRealmAccess() {
        return Stream.of("admin", List.of("admin"), Map.of("roles", "admin"), Map.of("roles", List.of(42, " ")));
    }

    @ParameterizedTest
    @MethodSource("malformedRealmAccess")
    void anUnexpectedClaimShapeGrantsNothingInsteadOfFailing(Object realmAccess) {
        assertThat(authorities(token(Map.of("realm_access", realmAccess)))).isEmpty();
    }

    private List<String> authorities(Jwt jwt) {
        return converter.convert(jwt).stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    private static Jwt token(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user")
                .issuedAt(Instant.parse("2026-11-03T12:00:00Z"))
                .claims(c -> c.putAll(claims))
                .build();
    }
}
