package io.github.tahausama210.driftwatch.security;

import io.github.tahausama210.driftwatch.common.web.RequestIdFilter;
import io.github.tahausama210.driftwatch.config.DriftwatchProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
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
 * Stateless bearer-token security, as three filter chains so that each credential is accepted only
 * where it belongs:
 *
 * <ol>
 *   <li><b>Agent endpoints.</b> {@code POST /api/v1/agents/enroll} requires the shared enrollment
 *       token and {@code POST /api/v1/scans} a per-host agent token. A dashboard JWT is never
 *       decoded here, so it fails like any unknown token.
 *   <li><b>Dashboard endpoints</b> ({@code /api/**}). An OIDC access token (see
 *       {@link DashboardTokenDecoder}) with the {@code viewer} realm role for reads, {@code admin}
 *       to decommission a host. An agent or enrollment token is not a JWT and fails to decode.
 *   <li><b>Everything else.</b> Health probes, Prometheus scraping and the OpenAPI document are
 *       public; all other paths are denied.
 * </ol>
 *
 * <p>{@code admin} implies {@code viewer} through a role hierarchy, mirroring the composite role in
 * the Keycloak realm, so the API does not depend on Keycloak expanding composites into the token.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String ENROLL_PATH = "/api/v1/agents/enroll";
    static final String SUBMIT_SCAN_PATH = "/api/v1/scans";
    static final String VIEWER = "viewer";
    static final String ADMIN = "admin";

    private static final RequestMatcher ENROLL = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, ENROLL_PATH);
    private static final RequestMatcher SUBMIT_SCAN =
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, SUBMIT_SCAN_PATH);

    @Bean
    @Order(1)
    SecurityFilterChain agentSecurityFilterChain(
            HttpSecurity http,
            DriftwatchProperties properties,
            AgentCredentialRepository credentials,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        ProblemDetailsSecurityHandler problemHandler = new ProblemDetailsSecurityHandler(exceptionResolver);

        // Each agent endpoint accepts exactly one kind of token: an agent token sent to the enroll
        // endpoint is checked against the enrollment secret (and fails), and vice versa.
        AuthenticationFilter bearerTokenFilter = new AuthenticationFilter(
                RequestMatcherDelegatingAuthenticationManagerResolver.builder()
                        .add(
                                ENROLL,
                                new ProviderManager(
                                        new EnrollmentTokenAuthenticationProvider(properties.enrollmentToken())))
                        .add(SUBMIT_SCAN, new ProviderManager(new AgentTokenAuthenticationProvider(credentials)))
                        .build(),
                new BearerTokenAuthenticationConverter());
        bearerTokenFilter.setSuccessHandler((request, response, authentication) -> {});
        bearerTokenFilter.setFailureHandler(problemHandler::commence);

        return statelessApi(http, problemHandler)
                .securityMatcher(new OrRequestMatcher(ENROLL, SUBMIT_SCAN))
                // Agents are not browsers; the dashboard's CORS policy does not extend to these.
                .cors(AbstractHttpConfigurer::disable)
                .addFilterBefore(bearerTokenFilter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(ENROLL)
                        .hasAuthority(EnrollmentTokenAuthenticationProvider.AUTHORITY)
                        .requestMatchers(SUBMIT_SCAN)
                        .hasAuthority(AgentTokenAuthenticationProvider.AUTHORITY)
                        .anyRequest()
                        .denyAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain dashboardSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder dashboardTokenDecoder,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        ProblemDetailsSecurityHandler problemHandler = new ProblemDetailsSecurityHandler(exceptionResolver);
        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(new RealmRoleAuthoritiesConverter());

        return statelessApi(http, problemHandler)
                .securityMatcher("/api/**")
                .cors(Customizer.withDefaults())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt ->
                                jwt.decoder(dashboardTokenDecoder).jwtAuthenticationConverter(authenticationConverter))
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler)
                        // By default the filter rethrows AuthenticationServiceException (raised when
                        // the JWKS endpoint is unreachable), which would bypass the problem rendering.
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(problemHandler::commence);
                                return filter;
                            }
                        }))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/hosts/*")
                        .hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/**")
                        .hasRole(VIEWER)
                        .anyRequest()
                        .denyAll())
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain publicSecurityFilterChain(
            HttpSecurity http, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        return statelessApi(http, new ProblemDetailsSecurityHandler(exceptionResolver))
                .cors(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        // Lets the servlet container render errors that escape a filter chain.
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/prometheus")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .build();
    }

    @Bean
    JwtDecoder dashboardTokenDecoder(DriftwatchProperties properties, Clock clock) {
        return DashboardTokenDecoder.create(properties.oidc(), clock);
    }

    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(ADMIN)
                .implies(VIEWER)
                .build();
    }

    /** What every chain shares: no session, no browser login flows, problem details for 401 and 403. */
    private static HttpSecurity statelessApi(HttpSecurity http, ProblemDetailsSecurityHandler problemHandler) {
        return http
                // CSRF attacks ride on credentials the browser attaches automatically (cookies). This
                // API keeps no session and only accepts credentials in an explicit Authorization
                // header, which a cross-site page cannot make the browser send, so there is nothing
                // to forge.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(problemHandler).accessDeniedHandler(problemHandler));
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
     * Lets a dashboard served from another origin (e.g. a dev server without a proxy) call the
     * dashboard endpoints with its bearer token. With no origins configured no CORS headers are ever
     * sent and browsers block cross-origin calls. The agent endpoints never send CORS headers.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(DriftwatchProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!properties.corsOrigins().isEmpty()) {
            CorsConfiguration cors = new CorsConfiguration();
            cors.setAllowedOrigins(properties.corsOrigins());
            cors.setAllowedMethods(List.of(HttpMethod.GET.name(), HttpMethod.DELETE.name()));
            cors.setAllowedHeaders(List.of(
                    HttpHeaders.ACCEPT, HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, RequestIdFilter.HEADER));
            cors.setExposedHeaders(List.of(RequestIdFilter.HEADER));
            cors.setMaxAge(Duration.ofHours(1));
            source.registerCorsConfiguration("/api/**", cors);
        }
        return source;
    }
}
