package ctu.soulspace.configuration;

import ctu.soulspace.security.GoogleLogin;
import ctu.soulspace.service.GoogleService;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
@ConditionalOnProperty(name = "soulspace.google.enabled", havingValue = "true")
public class GoogleConfig {
    @Bean
    public ClientRegistrationRepository googleRegistrations(
            @Value("${soulspace.google.client-id}") String clientId,
            @Value("${soulspace.google.client-secret}") String secret,
            @Value("${soulspace.google.redirect-uri}") String redirectUri) {
        if (clientId.isBlank() || secret.isBlank())
            throw new IllegalArgumentException("Google credentials are required when enabled");
        validateUrl(redirectUri);
        if (!URI.create(redirectUri).getPath().equals("/api/v1/auth/google/callback"))
            throw new IllegalArgumentException(
                    "Google callback path must be /api/v1/auth/google/callback");
        return new InMemoryClientRegistrationRepository(
                CommonOAuth2Provider.GOOGLE
                        .getBuilder("google")
                        .clientId(clientId)
                        .clientSecret(secret)
                        .redirectUri(redirectUri)
                        .scope("openid", "profile", "email")
                        .build());
    }

    @Bean
    public GoogleLogin googleLogin(
            GoogleService service,
            ClientRegistrationRepository registrations,
            SessionAuthenticationStrategy sessions,
            SecurityContextRepository contexts,
            @Value("${soulspace.google.frontend-url}") String frontend) {
        validateUrl(frontend);
        URI uri = URI.create(frontend);
        if (uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !Set.of("", "/").contains(uri.getPath()))
            throw new IllegalArgumentException("Google frontend URL must be an origin");
        return new GoogleLogin(
                service, registrations, sessions, contexts, frontend.replaceAll("/+$", ""));
    }

    private void validateUrl(String value) {
        URI uri = URI.create(value);
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !("https".equals(uri.getScheme())
                        || "http".equals(uri.getScheme())
                                && Set.of("localhost", "127.0.0.1").contains(uri.getHost())))
            throw new IllegalArgumentException(
                    "Use HTTPS or a loopback HTTP URL for Google sign-in");
    }
}
