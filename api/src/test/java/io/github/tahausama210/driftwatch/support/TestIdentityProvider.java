package io.github.tahausama210.driftwatch.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * A stand-in for Keycloak: an RSA signing key whose public half is served as a JWKS over real HTTP
 * on a random loopback port, and a builder for access tokens shaped like the realm's (issuer,
 * {@code aud}, {@code realm_access.roles}, {@code sub}, {@code preferred_username}). The API under
 * test fetches and caches the keys exactly as it would from Keycloak.
 */
public final class TestIdentityProvider implements AutoCloseable {

    public static final String ISSUER = "http://localhost:8400/auth/realms/driftwatch";
    public static final String AUDIENCE = "driftwatch-api";
    static final String JWKS_PATH = "/realms/driftwatch/protocol/openid-connect/certs";

    private final RSAKey signingKey;
    private final HttpServer server;
    private final AtomicInteger jwksRequests = new AtomicInteger();

    private TestIdentityProvider(RSAKey signingKey, int port) {
        this.signingKey = signingKey;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
        server.createContext(JWKS_PATH, exchange -> {
            jwksRequests.incrementAndGet();
            byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    /** Starts on a random free port with a fresh key. */
    public static TestIdentityProvider start() {
        return new TestIdentityProvider(newKey("test-key"), 0);
    }

    /** Starts serving the same key on a given port again, e.g. after an outage. */
    public TestIdentityProvider restart(int port) {
        return new TestIdentityProvider(signingKey, port);
    }

    public String jwksUri() {
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port() + JWKS_PATH;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** How many times the JWKS has been fetched since this server started. */
    public int jwksRequests() {
        return jwksRequests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** A valid token for {@code now} with the given realm roles; customise with the builder. */
    public Token token(Instant now, String... roles) {
        return new Token(now, List.of(roles));
    }

    public final class Token {

        private String issuer = ISSUER;
        private @Nullable Object audience = List.of(AUDIENCE, "account");
        private @Nullable String subject = UUID.randomUUID().toString();
        private String username = "service-account-driftwatch-smoke-viewer";
        private @Nullable Instant expiresAt;
        private final Instant issuedAt;
        private final List<String> roles;
        private RSAKey key = signingKey;
        private @Nullable String hmacSecret;
        private boolean unsigned;

        private Token(Instant now, List<String> roles) {
            this.issuedAt = now;
            this.expiresAt = now.plus(Duration.ofMinutes(5));
            this.roles = roles;
        }

        public Token issuer(String value) {
            issuer = value;
            return this;
        }

        public Token audience(@Nullable Object value) {
            audience = value;
            return this;
        }

        public Token subject(@Nullable String value) {
            subject = value;
            return this;
        }

        public Token username(String value) {
            username = value;
            return this;
        }

        public Token expiresAt(@Nullable Instant value) {
            expiresAt = value;
            return this;
        }

        /** Signs with a key the JWKS does not publish, under the published key id. */
        public Token signedByAnotherKey() {
            key = newKey(signingKey.getKeyID());
            return this;
        }

        /** HS256 keyed with the published public key: the classic algorithm-confusion forgery. */
        public Token hmacSignedWithThePublicKey() {
            hmacSecret = signingKey.toPublicJWK().toJSONString();
            return this;
        }

        /** {@code alg: none}: no signature at all. */
        public Token unsigned() {
            unsigned = true;
            return this;
        }

        public String subjectValue() {
            return subject;
        }

        public String sign() {
            // An expired token was also issued before it expired (Spring rejects exp before iat).
            Instant issued = expiresAt != null && !expiresAt.isAfter(issuedAt)
                    ? expiresAt.minus(Duration.ofMinutes(5))
                    : issuedAt;
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .issueTime(Date.from(issued))
                    .claim("preferred_username", username)
                    .claim("realm_access", Map.of("roles", roles))
                    .claim("scope", "profile email")
                    .claim("azp", "driftwatch-smoke-viewer");
            if (subject != null) {
                claims.subject(subject);
            }
            if (audience != null) {
                claims.claim("aud", audience);
            }
            if (expiresAt != null) {
                claims.expirationTime(Date.from(expiresAt));
            }
            if (unsigned) {
                return new PlainJWT(claims.build()).serialize();
            }
            try {
                if (hmacSecret != null) {
                    SignedJWT jwt = new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.HS256)
                                    .type(JOSEObjectType.JWT)
                                    .keyID(signingKey.getKeyID())
                                    .build(),
                            claims.build());
                    jwt.sign(new MACSigner(hmacSecret.getBytes(StandardCharsets.UTF_8)));
                    return jwt.serialize();
                }
                SignedJWT jwt = new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256)
                                .type(JOSEObjectType.JWT)
                                .keyID(key.getKeyID())
                                .build(),
                        claims.build());
                jwt.sign(new RSASSASigner(key));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static RSAKey newKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
