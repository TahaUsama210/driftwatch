package io.github.tahausama210.driftwatch.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Renders {@code 401} and {@code 403} by handing the exception to Spring MVC's exception resolvers,
 * so security failures produce the same problem details as every other error.
 *
 * <p>A rejected dashboard access token is logged with the validator's reason (e.g. a wrong issuer
 * or audience), never the token: a misconfigured {@code DRIFTWATCH_OIDC_ISSUER} is the most likely
 * deployment fault, and the client only ever sees a generic {@code 401}.
 */
final class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsSecurityHandler.class);

    private final HandlerExceptionResolver exceptionResolver;

    ProblemDetailsSecurityHandler(HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) {
        if (authException instanceof InvalidBearerTokenException) {
            log.atInfo().addKeyValue("reason", authException.getMessage()).log("Rejected a dashboard access token");
        }
        exceptionResolver.resolveException(request, response, null, authException);
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) {
        exceptionResolver.resolveException(request, response, null, accessDeniedException);
    }
}
