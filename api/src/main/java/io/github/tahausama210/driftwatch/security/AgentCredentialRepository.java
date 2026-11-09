package io.github.tahausama210.driftwatch.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Resolves agent token hashes to hosts. */
@Repository
public class AgentCredentialRepository {

    private final JdbcClient jdbc;

    public AgentCredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Uses the unique index on {@code hosts.token_hash}. */
    public Optional<UUID> findHostIdByTokenHash(String tokenHash) {
        return jdbc.sql("SELECT id FROM hosts WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .query(UUID.class)
                .optional();
    }
}
