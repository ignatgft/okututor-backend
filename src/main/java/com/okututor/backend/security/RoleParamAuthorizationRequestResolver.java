package com.okututor.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * прокидывает query-параметр `role` (?role=STUDENT|TUTOR) из начального
 * /oauth2/authorization/google request through the OAuth flow so the success
 * обработчик мог применить её при создании аккаунта.
 */
public class RoleParamAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public RoleParamAuthorizationRequestResolver(ClientRegistrationRepository repository) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(repository, "/oauth2/authorization");
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return withRole(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return withRole(delegate.resolve(request, clientRegistrationId), request);
    }

    private OAuth2AuthorizationRequest withRole(OAuth2AuthorizationRequest resolved, HttpServletRequest request) {
        if (resolved == null) {
            return null;
        }
        String role = request.getParameter("role");
        if (role == null || role.isBlank()) {
            return resolved;
        }
        Map<String, Object> additional = new HashMap<>(resolved.getAdditionalParameters());
        additional.put("role", role.trim().toUpperCase());
        return OAuth2AuthorizationRequest.from(resolved).additionalParameters(additional).build();
    }
}
