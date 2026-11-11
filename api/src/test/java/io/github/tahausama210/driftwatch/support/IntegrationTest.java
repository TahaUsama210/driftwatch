package io.github.tahausama210.driftwatch.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base class for almost every integration test: dashboard tokens are signed by one in-test
 * {@link TestIdentityProvider} whose JWKS the API fetches over HTTP, and all subclasses share one
 * cached application context.
 */
public abstract class IntegrationTest extends ApplicationTest {

    /** Signs the dashboard tokens of every test that shares the default context. */
    protected static final TestIdentityProvider IDENTITY_PROVIDER = TestIdentityProvider.start();

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        registry.add("driftwatch.oidc.issuer", () -> TestIdentityProvider.ISSUER);
        registry.add("driftwatch.oidc.jwks-uri", IDENTITY_PROVIDER::jwksUri);
    }

    /** A token with the realm's {@code viewer} role, valid at the test clock's current time. */
    @Override
    protected String viewerToken() {
        return IDENTITY_PROVIDER.token(clock.instant(), "viewer").sign();
    }

    /** A token like the realm issues for an admin: {@code admin} plus the composite's {@code viewer}. */
    @Override
    protected String adminToken() {
        return IDENTITY_PROVIDER
                .token(clock.instant(), "admin", "viewer")
                .username("admin")
                .sign();
    }

    @Override
    protected RequestPostProcessor asViewer() {
        return bearer(viewerToken());
    }

    @Override
    protected RequestPostProcessor asAdmin() {
        return bearer(adminToken());
    }
}
