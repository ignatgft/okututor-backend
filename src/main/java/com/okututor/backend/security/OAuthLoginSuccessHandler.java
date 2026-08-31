package com.okututor.backend.security;

import com.okututor.backend.auth.AuthService;
import com.okututor.backend.auth.dto.AuthTokensResponse;

import com.okututor.backend.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * после успешного входа через Google редиректит на
 * {FRONTEND_URL}/oauth/callback?access_token=..&refresh_token=..
 * (контракт через query string, который ждёт PgOAuthCallback; задокументирован в OpenAPI).
 */
@Component
public class OAuthLoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginSuccessHandler.class);

    private final AuthService authService;
    private final GoogleProvisioner provisioner;
    private final com.okututor.backend.common.config.AppProperties properties;

    public OAuthLoginSuccessHandler(AuthService authService,
                                    GoogleProvisioner provisioner,
                                    com.okututor.backend.common.config.AppProperties properties) {
        this.authService = authService;
        this.provisioner = provisioner;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
            // эскалация ролей через ?role= запрещена: OAuth всегда выдаёт STUDENT
            User user = provisioner.provision(token.getPrincipal());

            AuthTokensResponse tokens = authService.buildTokenPair(user);
            String redirectUrl = UriComponentsBuilder.fromHttpUrl(properties.getFrontendUrl())
                    .path("/oauth/callback")
                    .queryParam("access_token", tokens.access_token())
                    .queryParam("refresh_token", tokens.refresh_token())
                    .build()
                    .encode()
                    .toUriString();
            getRedirectStrategy().sendRedirect(request, response, redirectUrl);
        } catch (Exception ex) {
            log.error("OAuth login failed", ex);
            redirectError(response);
        }
    }

    private void redirectError(HttpServletResponse response) throws IOException {
        String url = UriComponentsBuilder.fromHttpUrl(properties.getFrontendUrl())
                .path("/oauth/callback")
                .queryParam("error", "oauth_failed")
                .build()
                .toUriString();
        response.sendRedirect(url);
    }

}
