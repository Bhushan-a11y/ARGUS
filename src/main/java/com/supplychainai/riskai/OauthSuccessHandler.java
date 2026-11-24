package com.supplychainai.riskai;

import java.io.IOException;
import java.util.Locale;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplychainai.riskai.entity.AuthProvider;
import com.supplychainai.riskai.entity.Supplier;
import com.supplychainai.riskai.service.JwtService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component 
public class OauthSuccessHandler implements AuthenticationSuccessHandler {
    private final SupplierAuthenticationService supplierAuthService;
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public OauthSuccessHandler(
            SupplierAuthenticationService supplierAuthService,
            JwtService jwtService,
            ObjectMapper objectMapper) {
        this.supplierAuthService = supplierAuthService;
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication)
            throws IOException, ServletException {

        try {
            if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)
                    || !(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("unsupported_identity"),
                        "An OIDC identity is required");
            }

            AuthProvider provider = switch (
                    oauthToken.getAuthorizedClientRegistrationId()
                            .toLowerCase(Locale.ROOT)) {
                case "google" -> AuthProvider.GOOGLE;
                default -> throw new OAuth2AuthenticationException(
                        new OAuth2Error("unsupported_provider"),
                        "Unsupported identity provider");
            };

            String externalId = oidcUser.getSubject();
            String email = oidcUser.getEmail();
            String name = oidcUser.getFullName();

            if (email == null || email.isBlank()) {
                Object emailClaim = oidcUser.getClaims().get("email");
                Object usernameClaim =
                        oidcUser.getClaims().get("preferred_username");

                if (emailClaim instanceof String value && !value.isBlank()) {
                    email = value;
                } else if (usernameClaim instanceof String value
                        && value.contains("@")) {
                    // Microsoft may supply preferred_username instead
                    // of an email claim. This is contact information,
                    // not the stable identity key.
                    email = value;
                }
            }

            if (provider == AuthProvider.GOOGLE) {
                Object verified = oidcUser.getClaims().get("email_verified");
                if (Boolean.FALSE.equals(verified)) {
                    throw new OAuth2AuthenticationException(
                            new OAuth2Error("unverified_email"),
                            "Google email must be verified");
                }
            }

            if (name == null || name.isBlank()) {
                Object nameClaim = oidcUser.getClaims().get("name");
                name = nameClaim instanceof String value ? value : email;
            }

            Supplier supplier = supplierAuthService.authenticateOrRegister(
                    provider, externalId, email, name);

            JwtService.IssuedToken issuedToken =
                    jwtService.generateToken(supplier);

            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");

            objectMapper.writeValue(response.getWriter(), issuedToken);

        } catch (OAuth2AuthenticationException ex) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");

            objectMapper.writeValue(response.getWriter(),
                    java.util.Map.of(
                            "error", "authentication_failed",
                            "message", ex.getMessage()));
        }
    }

}
