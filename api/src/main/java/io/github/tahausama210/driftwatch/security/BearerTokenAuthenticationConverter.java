package io.github.tahausama210.driftwatch.security;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Reads an RFC 6750 bearer token from the {@code Authorization} header. Returns {@code null} when the
 * header is absent (the request continues anonymously and is rejected by authorization), and fails
 * when the header is present but malformed.
 */
final class BearerTokenAuthenticationConverter implements AuthenticationConverter {

    private static final String SCHEME = "Bearer ";

    @Override
    public @Nullable BearerTokenAuthenticationRequest convert(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return null;
        }
        if (!header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            throw new BadCredentialsException("Authorization header does not use the Bearer scheme");
        }
        String token = header.substring(SCHEME.length()).strip();
        if (token.isEmpty() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new BadCredentialsException("Malformed bearer token");
        }
        return new BearerTokenAuthenticationRequest(token);
    }
}
