package ctu.soulspace.configuration;

import ctu.soulspace.constants.AuthMessages;
import ctu.soulspace.repositories.UserRepository;
import ctu.soulspace.security.AccessDeniedHandler;
import ctu.soulspace.security.ApiErrorWriter;
import ctu.soulspace.security.AuthenticationEntryPoint;
import ctu.soulspace.security.GoogleLogin;
import ctu.soulspace.security.SessionGuardFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityContextRepository securityContextRepository() {
        HttpSessionSecurityContextRepository sessions = new HttpSessionSecurityContextRepository();
        sessions.setDisableUrlRewriting(true);
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), sessions);
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(
            CsrfTokenRepository csrfTokenRepository, SessionRegistry sessionRegistry) {
        CsrfAuthenticationStrategy csrf = new CsrfAuthenticationStrategy(csrfTokenRepository);
        csrf.setRequestHandler(new XorCsrfTokenRequestAttributeHandler());
        return new CompositeSessionAuthenticationStrategy(
                List.of(
                        new ChangeSessionIdAuthenticationStrategy(),
                        csrf,
                        new RegisterSessionAuthenticationStrategy(sessionRegistry)));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository contextRepository,
            CsrfTokenRepository csrfRepository,
            SessionRegistry sessions,
            UserRepository users,
            ApiErrorWriter errors,
            AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler deniedHandler,
            JsonMapper jsonMapper,
            ObjectProvider<GoogleLogin> googleProvider,
            @Value("${server.servlet.session.cookie.name:SOULSPACESESSION}") String cookieName)
            throws Exception {
        http.securityContext(
                        context ->
                                context.securityContextRepository(contextRepository)
                                        .requireExplicitSave(true))
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(
                        csrf ->
                                csrf.csrfTokenRepository(csrfRepository)
                                        .csrfTokenRequestHandler(
                                                new XorCsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/auth/csrf",
                                                "/api/v1/auth/google/onboarding",
                                                "/api/v1/auth/google/callback",
                                                "/actuator/health")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/soulspaces",
                                                "/api/v1/blogs/*/blogs",
                                                "/api/v1/blogs/*/comments",
                                                "/api/v1/users/*/shared-blogs",
                                                "/api/v1/blogs/*",
                                                "/api/v1/users",
                                                "/api/v1/users/*",
                                                "/api/v1/media/*")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/auth/log-in",
                                                "/api/v1/auth/google/start",
                                                "/api/v1/auth/google/onboarding",
                                                "/api/v1/users/sign-up")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(entryPoint)
                                        .accessDeniedHandler(deniedHandler))
                .logout(
                        logout ->
                                logout.logoutUrl("/api/v1/auth/log-out")
                                        .addLogoutHandler(
                                                (request, response, authentication) -> {
                                                    var session = request.getSession(false);
                                                    if (session != null) {
                                                        sessions.removeSessionInformation(
                                                                session.getId());
                                                    }
                                                })
                                        .invalidateHttpSession(true)
                                        .clearAuthentication(true)
                                        .deleteCookies(cookieName)
                                        .permitAll()
                                        .logoutSuccessHandler(
                                                (request, response, authentication) -> {
                                                    log.info(
                                                            "event=logout user_id={}",
                                                            authentication == null
                                                                    ? "anonymous"
                                                                    : authentication.getName());
                                                    response.setContentType(
                                                            "application/json;charset=UTF-8");
                                                    response.setHeader("Cache-Control", "no-store");
                                                    jsonMapper.writeValue(
                                                            response.getOutputStream(),
                                                            Map.of(
                                                                    "message",
                                                                    AuthMessages.LOGOUT_SUCCESS));
                                                }))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .addFilterBefore(
                        new SessionGuardFilter(sessions, users, errors), LogoutFilter.class);
        GoogleLogin google = googleProvider.getIfAvailable();
        if (google != null) {
            http.oauth2Login(
                    login ->
                            login.loginPage("/api/v1/auth/google/start")
                                    .redirectionEndpoint(
                                            endpoint ->
                                                    endpoint.baseUri(
                                                            "/api/v1/auth/google/callback"))
                                    .authorizationEndpoint(
                                            endpoint ->
                                                    endpoint.authorizationRequestRepository(google)
                                                            .authorizationRequestResolver(
                                                                    new OAuth2AuthorizationRequestResolver() {
                                                                        @Override
                                                                        public
                                                                        OAuth2AuthorizationRequest
                                                                                resolve(
                                                                                        HttpServletRequest
                                                                                                request) {
                                                                            return null;
                                                                        }

                                                                        @Override
                                                                        public
                                                                        OAuth2AuthorizationRequest
                                                                                resolve(
                                                                                        HttpServletRequest
                                                                                                request,
                                                                                        String id) {
                                                                            return null;
                                                                        }
                                                                    }))
                                    .authorizedClientRepository(new GoogleLogin.TransientClients())
                                    .successHandler(google::success)
                                    .failureHandler(google::rejected)
                                    .withObjectPostProcessor(
                                            new ObjectPostProcessor<
                                                    OAuth2LoginAuthenticationFilter>() {
                                                @Override
                                                public <O extends OAuth2LoginAuthenticationFilter>
                                                        O postProcess(O filter) {
                                                    filter.setSecurityContextRepository(
                                                            new NullSecurityContextRepository());
                                                    filter.setSessionAuthenticationStrategy(
                                                            (authentication,
                                                                    request,
                                                                    response) -> {});
                                                    return filter;
                                                }
                                            }));
        }
        return http.build();
    }
}
