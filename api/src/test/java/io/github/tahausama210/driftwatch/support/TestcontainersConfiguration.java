package io.github.tahausama210.driftwatch.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A throwaway Postgres for the integration tests, wired into the datasource by
 * {@code @ServiceConnection}. Most integration tests share one application context through Spring's
 * test context cache; the few that need different settings (an unreachable identity provider, a real
 * Keycloak) get their own context but reuse this one container, since the tests run one at a time
 * and each starts by truncating the tables. It stops when the test JVM exits.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18.4-alpine"));

    /** Destroying a context must not stop the container another cached context still uses. */
    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }
}
