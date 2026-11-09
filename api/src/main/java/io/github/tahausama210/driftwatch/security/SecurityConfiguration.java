package io.github.tahausama210.driftwatch.security;

import io.github.tahausama210.driftwatch.common.web.RequestIdFilter;
import io.github.tahausama210.driftwatch.config.DriftwatchProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationFilter;
import org.springframework.security.web.authentication.RequestMatcherDelegatingAuthenticationManagerResolver;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Stateless bearer-token security.
 *
 * <ul>
 *   <li>{@code POST /api/v1/agents/enroll} requires the shared enrollment token.
 *   <li>{@code POST /api/v1/scans} requires a per-host agent token.
 *   <li>Read endpoints, health probes, Prometheus scraping and the OpenAPI document are public.
 *   <li>Everything else is denied.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String ENROLL_PATH = "/api/v1/agents/enroll";
    static final String SUBMIT_SCAN_PATH = "/api/v1/scans";

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            DriftwatchProperties properties,
            AgentCredentialRepository credentials,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        ProblemDetailsSecurityHandler problemHandler = new ProblemDetailsSecurityHandler(exceptionResolver);
        RequestMatcher enroll = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, ENROLL_PATH);
        RequestMatcher submitScan = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, SUBMIT_SCAN_PATH);

        // Each agent endpoint accepts exactly one kind of token: an agent token sent to the enroll
        // endpoint is checked against the enrollment secret (and fails), and vice versa.
        AuthenticationFilter bearerTokenFilter = new AuthenticationFilter(
                RequestMatcherDelegatingAuthenticationManagerResolver.builder()
                        .add(
                                enroll,
                                new ProviderManager(
                                        new EnrollmentTokenAuthenticationProvider(properties.enrollmentToken())))
                        .add(submitScan, new ProviderManager(new AgentTokenAuthenticationProvider(credentials)))
                        .build(),
                new BearerTokenAuthenticationConverter());
        bearerTokenFilter.setRequestMatcher(new OrRequestMatcher(enroll, submitScan));
        bearerTokenFilter.setSuccessHandler((request, response, authentication) -> {});
        bearerTokenFilter.setFailureHandler(problemHandler::commence);

        return http
                // CSRF attacks ride on credentials the browser attaches automatically (cookies). This
                // API keeps no session and only accepts writes with an explicit Authorization header,
                // which a cross-site page cannot make the browser send, so there is nothing to forge.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .addFilterBefore(bearerTokenFilter, AnonymousAuthenticationFilter.class)
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(problemHandler).accessDeniedHandler(problemHandler))
                .authorizeHttpRequests(authorize -> authorize
                        // Lets the servlet container render errors that escape the filter chain.
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(enroll)
                        .hasAuthority(EnrollmentTokenAuthenticationProvider.AUTHORITY)
                        .requestMatchers(submitScan)
                        .hasAuthority(AgentTokenAuthenticationProvider.AUTHORITY)
                        // Phase 1 tradeoff: the read API is public; dashboard authentication is phase 2.
                        .requestMatchers(HttpMethod.GET, "/api/v1/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/prometheus")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .build();
    }

    /**
     * Renders requests that Spring Security's firewall rejects (e.g. a path containing {@code //} or
     * {@code ;}) as problem details too; the default handler sends a bare {@code 400} that Boot's
     * error page turns into a non-RFC 9457 body. Falls back to that bare {@code 400} should the
     * exception resolver be unable to render the problem.
     */
    @Bean
    RequestRejectedHandler requestRejectedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        return (request, response, rejection) -> {
            if (exceptionResolver.resolveException(request, response, null, rejection) == null) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            }
        };
    }

    /**
     * The dashboard only reads, so browsers from the configured origins may only issue {@code GET}s.
     * With no origins configured no CORS headers are ever sent and browsers block cross-origin calls.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(DriftwatchProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!properties.corsOrigins().isEmpty()) {
            CorsConfiguration cors = new CorsConfiguration();
            cors.setAllowedOrigins(properties.corsOrigins());
            cors.setAllowedMethods(List.of(HttpMethod.GET.name()));
            cors.setAllowedHeaders(List.of(HttpHeaders.ACCEPT, HttpHeaders.CONTENT_TYPE, RequestIdFilter.HEADER));
            cors.setExposedHeaders(List.of(RequestIdFilter.HEADER));
            cors.setMaxAge(Duration.ofHours(1));
            source.registerCorsConfiguration("/api/**", cors);
        }
        return source;
    }
}
