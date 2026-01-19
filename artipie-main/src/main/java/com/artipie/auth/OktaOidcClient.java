/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.auth;

import com.artipie.http.log.EcsLogger;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;

/**
 * Minimal Okta OIDC / Authentication API client used by {@link AuthFromOkta}.
 */
public final class OktaOidcClient {

    private final HttpClient client;

    private final String issuer;

    private final String authnUrl;

    private final String authorizeUrl;

    private final String tokenUrl;

    private final String userinfoUrl;

    private final String clientId;

    private final String clientSecret;

    private final String redirectUri;

    private final String scope;

    private final String groupsClaim;

    public OktaOidcClient(
        final String issuer,
        final String authnUrlOverride,
        final String authorizeUrlOverride,
        final String tokenUrlOverride,
        final String clientId,
        final String clientSecret,
        final String redirectUri,
        final String scope,
        final String groupsClaim
    ) {
        this.client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        this.issuer = Objects.requireNonNull(issuer);
        this.clientId = Objects.requireNonNull(clientId);
        this.clientSecret = Objects.requireNonNull(clientSecret);
        this.redirectUri = redirectUri != null ? redirectUri : "https://artipie.local/okta/callback";
        this.scope = scope != null ? scope : "openid profile groups";
        this.groupsClaim = groupsClaim != null ? groupsClaim : "groups";
        final URI issuerUri = URI.create(issuer);
        final String domainBase = issuerUri.getScheme() + "://" + issuerUri.getHost()
            + (issuerUri.getPort() == -1 ? "" : ":" + issuerUri.getPort());
        this.authnUrl = authnUrlOverride != null && !authnUrlOverride.isEmpty()
            ? authnUrlOverride
            : domainBase + "/api/v1/authn";
        // Determine OIDC base URL:
        // - If issuer contains /oauth2/ (e.g. https://domain/oauth2/default), use issuer directly
        // - Otherwise (org-level issuer like https://domain), append /oauth2
        final String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        final String oidcBase;
        if (base.contains("/oauth2")) {
            oidcBase = base;
        } else {
            oidcBase = base + "/oauth2";
        }
        this.authorizeUrl = authorizeUrlOverride != null && !authorizeUrlOverride.isEmpty()
            ? authorizeUrlOverride
            : oidcBase + "/v1/authorize";
        this.tokenUrl = tokenUrlOverride != null && !tokenUrlOverride.isEmpty()
            ? tokenUrlOverride
            : oidcBase + "/v1/token";
        this.userinfoUrl = oidcBase + "/v1/userinfo";
    }

    public OktaAuthResult authenticate(
        final String username,
        final String password,
        final String mfaCode
    ) throws IOException, InterruptedException {
        EcsLogger.info("com.artipie.auth")
            .message("Starting Okta authentication")
            .eventCategory("authentication")
            .eventAction("login")
            .field("user.name", username)
            .field("okta.issuer", this.issuer)
            .field("okta.authn_url", this.authnUrl)
            .field("okta.authorize_url", this.authorizeUrl)
            .log();
        final JsonObject authnReq = Json.createObjectBuilder()
            .add("username", username)
            .add("password", password)
            .build();
        final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(this.authnUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(authnReq.toString()))
            .build();
        final HttpResponse<String> response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            String errorCode = "";
            String errorSummary = "";
            try {
                final JsonObject errBody = json(response.body());
                errorCode = errBody.getString("errorCode", "");
                errorSummary = errBody.getString("errorSummary", "");
            } catch (final Exception ignored) {
                // Response may not be JSON
            }
            EcsLogger.error("com.artipie.auth")
                .message("Okta /authn failed")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .field("http.status", response.statusCode())
                .field("okta.url", this.authnUrl)
                .field("okta.error_code", errorCode)
                .field("okta.error_summary", errorSummary)
                .log();
            return null;
        }
        final JsonObject body = json(response.body());
        final String status = body.getString("status", "");
        String sessionToken = null;
        if ("SUCCESS".equals(status)) {
            sessionToken = body.getString("sessionToken", null);
        } else if ("MFA_REQUIRED".equals(status) || "MFA_CHALLENGE".equals(status)) {
            sessionToken = handleMfa(body, mfaCode, username);
        }
        if (sessionToken == null || sessionToken.isEmpty()) {
            EcsLogger.error("com.artipie.auth")
                .message("Okta authentication did not return sessionToken")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .log();
            return null;
        }
        EcsLogger.info("com.artipie.auth")
            .message("Got sessionToken, exchanging for code")
            .eventCategory("authentication")
            .eventAction("login")
            .field("user.name", username)
            .log();
        final String code = exchangeSessionForCode(sessionToken, username);
        if (code == null || code.isEmpty()) {
            EcsLogger.error("com.artipie.auth")
                .message("Failed to exchange sessionToken for code")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .log();
            return null;
        }
        EcsLogger.info("com.artipie.auth")
            .message("Got authorization code, exchanging for tokens")
            .eventCategory("authentication")
            .eventAction("login")
            .field("user.name", username)
            .log();
        final TokenResponse tokens = exchangeCodeForTokens(code, username);
        if (tokens == null || tokens.idToken == null) {
            EcsLogger.error("com.artipie.auth")
                .message("Failed to exchange code for tokens")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .field("tokens_null", tokens == null)
                .log();
            return null;
        }
        EcsLogger.info("com.artipie.auth")
            .message("Got tokens, parsing id_token")
            .eventCategory("authentication")
            .eventAction("login")
            .field("user.name", username)
            .log();
        return parseIdToken(tokens.idToken, tokens.accessToken, username);
    }

    private String handleMfa(
        final JsonObject authnBody,
        final String mfaCode,
        final String username
    ) throws IOException, InterruptedException {
        final String stateToken = authnBody.getString("stateToken", null);
        if (stateToken == null) {
            return null;
        }
        final JsonObject embedded = authnBody.getJsonObject("_embedded");
        if (embedded == null) {
            return null;
        }
        final JsonArray factors = embedded.getJsonArray("factors");
        if (factors == null || factors.isEmpty()) {
            return null;
        }
        // 1) Code-based MFA (TOTP / token:* factors) when mfaCode is provided
        if (mfaCode != null && !mfaCode.isEmpty()) {
            for (int idx = 0; idx < factors.size(); idx = idx + 1) {
                final JsonObject factor = factors.getJsonObject(idx);
                final String type = factor.getString("factorType", "");
                if (!type.startsWith("token:")) {
                    continue;
                }
                final String href = verifyHref(factor);
                if (href == null) {
                    continue;
                }
                final JsonObject req = Json.createObjectBuilder()
                    .add("stateToken", stateToken)
                    .add("passCode", mfaCode)
                    .build();
                final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(href))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(req.toString()))
                    .build();
                final HttpResponse<String> resp = this.client.send(
                    request, HttpResponse.BodyHandlers.ofString()
                );
                if (resp.statusCode() / 100 != 2) {
                    continue;
                }
                final JsonObject body = json(resp.body());
                final String status = body.getString("status", "");
                if ("SUCCESS".equals(status)) {
                    return body.getString("sessionToken", null);
                }
            }
        }
        // 2) Out-of-band MFA (e.g. push) when no mfaCode is provided
        for (int idx = 0; idx < factors.size(); idx = idx + 1) {
            final JsonObject factor = factors.getJsonObject(idx);
            final String type = factor.getString("factorType", "");
            if (!"push".equals(type)) {
                continue;
            }
            final String href = verifyHref(factor);
            if (href == null) {
                continue;
            }
            final JsonObject req = Json.createObjectBuilder()
                .add("stateToken", stateToken)
                .build();
            // Initial verify call to trigger push
            JsonObject body = sendMfaVerify(href, req);
            if (body == null) {
                continue;
            }
            String status = body.getString("status", "");
            if ("SUCCESS".equals(status)) {
                return body.getString("sessionToken", null);
            }
            // Poll verify endpoint for limited time while push is pending
            final int maxAttempts = 30;
            for (int attempt = 0; attempt < maxAttempts; attempt = attempt + 1) {
                if ("SUCCESS".equals(status)) {
                    final String token = body.getString("sessionToken", null);
                    EcsLogger.info("com.artipie.auth")
                        .message("Okta MFA push verified successfully")
                        .eventCategory("authentication")
                        .eventAction("mfa")
                        .eventOutcome("success")
                        .field("user.name", username)
                        .log();
                    return token;
                }
                if (!"MFA_CHALLENGE".equals(status) && !"MFA_REQUIRED".equals(status)) {
                    break;
                }
                try {
                    Thread.sleep(1000L);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
                body = sendMfaVerify(href, req);
                if (body == null) {
                    break;
                }
                status = body.getString("status", "");
            }
        }
        EcsLogger.error("com.artipie.auth")
            .message("Okta MFA verification failed")
            .eventCategory("authentication")
            .eventAction("login")
            .eventOutcome("failure")
            .field("user.name", username)
            .log();
        return null;
    }

    private JsonObject sendMfaVerify(final String href, final JsonObject req)
        throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(href))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(req.toString()))
            .build();
        final HttpResponse<String> resp = this.client.send(
            request, HttpResponse.BodyHandlers.ofString()
        );
        if (resp.statusCode() / 100 != 2) {
            return null;
        }
        return json(resp.body());
    }

    private static String verifyHref(final JsonObject factor) {
        final JsonObject links = factor.getJsonObject("_links");
        if (links == null || !links.containsKey("verify")) {
            return null;
        }
        final JsonObject verify = links.getJsonObject("verify");
        return verify.getString("href", null);
    }

    private String exchangeSessionForCode(final String sessionToken, final String username)
        throws IOException, InterruptedException {
        final String state = Long.toHexString(Double.doubleToLongBits(Math.random()));
        final String query = "client_id=" + enc(this.clientId)
            + "&response_type=code"
            + "&scope=" + enc(this.scope)
            + "&redirect_uri=" + enc(this.redirectUri)
            + "&state=" + enc(state)
            + "&sessionToken=" + enc(sessionToken);
        final URI uri = URI.create(this.authorizeUrl + "?" + query);
        final HttpRequest request = HttpRequest.newBuilder()
            .uri(uri)
            .GET()
            .build();
        final HttpResponse<Void> resp = this.client.send(
            request, HttpResponse.BodyHandlers.discarding()
        );
        if (resp.statusCode() / 100 != 3) {
            EcsLogger.error("com.artipie.auth")
                .message("Okta authorize did not redirect")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .field("http.status", resp.statusCode())
                .field("okta.authorize_url", this.authorizeUrl)
                .field("okta.issuer", this.issuer)
                .log();
            return null;
        }
        final List<String> locations = resp.headers().allValues("Location");
        if (locations.isEmpty()) {
            EcsLogger.error("com.artipie.auth")
                .message("Okta authorize redirect missing Location header")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .field("http.status", resp.statusCode())
                .log();
            return null;
        }
        final String location = locations.get(0);
        EcsLogger.info("com.artipie.auth")
            .message("Okta authorize redirect received")
            .eventCategory("authentication")
            .eventAction("login")
            .field("user.name", username)
            .field("okta.redirect_location", location)
            .log();
        final URI loc = URI.create(location);
        final String queryStr = loc.getQuery();
        if (queryStr == null) {
            EcsLogger.error("com.artipie.auth")
                .message("Okta authorize redirect has no query string")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .field("okta.redirect_location", location)
                .log();
            return null;
        }
        final String[] parts = queryStr.split("&");
        String code = null;
        String returnedState = null;
        String error = null;
        String errorDesc = null;
        for (final String part : parts) {
            final int idx = part.indexOf('=');
            if (idx < 0) {
                continue;
            }
            final String key = part.substring(0, idx);
            final String val = part.substring(idx + 1);
            if ("code".equals(key)) {
                code = urlDecode(val);
            } else if ("state".equals(key)) {
                returnedState = urlDecode(val);
            } else if ("error".equals(key)) {
                error = urlDecode(val);
            } else if ("error_description".equals(key)) {
                errorDesc = urlDecode(val);
            }
        }
        if (error != null) {
            EcsLogger.error("com.artipie.auth")
                .message("Okta authorize returned error")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .field("okta.error", error)
                .field("okta.error_description", errorDesc != null ? errorDesc : "")
                .log();
            return null;
        }
        if (code == null || !state.equals(returnedState)) {
            EcsLogger.error("com.artipie.auth")
                .message("Okta authorize missing code or state mismatch")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .field("code_present", code != null)
                .field("state_match", state.equals(returnedState))
                .log();
            return null;
        }
        return code;
    }

    private TokenResponse exchangeCodeForTokens(final String code, final String username)
        throws IOException, InterruptedException {
        final String body = "grant_type=authorization_code"
            + "&code=" + enc(code)
            + "&redirect_uri=" + enc(this.redirectUri);
        final String basic = Base64.getEncoder().encodeToString(
            (this.clientId + ":" + this.clientSecret).getBytes(StandardCharsets.UTF_8)
        );
        final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(this.tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", "Basic " + basic)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        final HttpResponse<String> resp = this.client.send(
            request, HttpResponse.BodyHandlers.ofString()
        );
        if (resp.statusCode() / 100 != 2) {
            EcsLogger.error("com.artipie.auth")
                .message("Okta token endpoint failed")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .field("http.status", resp.statusCode())
                .log();
            return null;
        }
        final JsonObject json = json(resp.body());
        return new TokenResponse(
            json.getString("id_token", null),
            json.getString("access_token", null)
        );
    }

    private static final class TokenResponse {
        final String idToken;
        final String accessToken;

        TokenResponse(final String idToken, final String accessToken) {
            this.idToken = idToken;
            this.accessToken = accessToken;
        }
    }

    private OktaAuthResult parseIdToken(
        final String idToken,
        final String accessToken,
        final String username
    ) {
        try {
            final String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                EcsLogger.error("com.artipie.auth")
                    .message("Invalid id_token format")
                    .eventCategory("authentication")
                    .eventAction("login")
                    .eventOutcome("failure")
                    .field("user.name", username)
                    .log();
                return null;
            }
            final byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            final JsonObject json = json(new String(payload, StandardCharsets.UTF_8));
            final String iss = json.getString("iss", "");
            if (!this.issuer.equals(iss)) {
                EcsLogger.error("com.artipie.auth")
                    .message("id_token issuer mismatch")
                    .eventCategory("authentication")
                    .eventAction("login")
                    .eventOutcome("failure")
                    .field("user.name", username)
                    .field("okta.expected_issuer", this.issuer)
                    .field("okta.actual_issuer", iss)
                    .log();
                return null;
            }
            final String aud;
            if (json.containsKey("aud") && json.get("aud").getValueType() == javax.json.JsonValue.ValueType.ARRAY) {
                final JsonArray arr = json.getJsonArray("aud");
                aud = arr.isEmpty() ? "" : arr.getString(0, "");
            } else {
                aud = json.getString("aud", "");
            }
            if (!this.clientId.equals(aud)) {
                EcsLogger.error("com.artipie.auth")
                    .message("id_token audience mismatch")
                    .eventCategory("authentication")
                    .eventAction("login")
                    .eventOutcome("failure")
                    .field("user.name", username)
                    .field("okta.expected_aud", this.clientId)
                    .field("okta.actual_aud", aud)
                    .log();
                return null;
            }
            String uname = json.getString("preferred_username", null);
            if (uname == null || uname.isEmpty()) {
                uname = json.getString("sub", username);
            }
            // Try to get email and groups from id_token first
            String email = json.getString("email", null);
            final List<String> groups = new ArrayList<>();
            if (json.containsKey(this.groupsClaim)) {
                extractGroups(json, groups);
            }
            // If groups or email missing, fetch from userinfo endpoint
            if ((groups.isEmpty() || email == null) && accessToken != null) {
                final JsonObject userinfo = fetchUserInfo(accessToken, username);
                if (userinfo != null) {
                    if (email == null) {
                        email = userinfo.getString("email", null);
                    }
                    if (groups.isEmpty() && userinfo.containsKey(this.groupsClaim)) {
                        extractGroups(userinfo, groups);
                    }
                }
            }
            EcsLogger.info("com.artipie.auth")
                .message("Okta authentication successful")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("success")
                .field("user.name", uname)
                .field("user.email", email != null ? email : "")
                .field("okta.groups", String.join(",", groups))
                .field("okta.groups_claim", this.groupsClaim)
                .log();
            return new OktaAuthResult(uname, email, groups);
        } catch (final IllegalArgumentException err) {
            EcsLogger.error("com.artipie.auth")
                .message("Failed to parse Okta id_token")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .error(err)
                .log();
            return null;
        }
    }

    private void extractGroups(final JsonObject json, final List<String> groups) {
        if (json.get(this.groupsClaim).getValueType() == javax.json.JsonValue.ValueType.ARRAY) {
            final JsonArray arr = json.getJsonArray(this.groupsClaim);
            for (int i = 0; i < arr.size(); i = i + 1) {
                groups.add(arr.getString(i, ""));
            }
        } else {
            final String single = json.getString(this.groupsClaim, "");
            if (!single.isEmpty()) {
                groups.add(single);
            }
        }
    }

    private JsonObject fetchUserInfo(final String accessToken, final String username) {
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(this.userinfoUrl))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
            final HttpResponse<String> resp = this.client.send(
                request, HttpResponse.BodyHandlers.ofString()
            );
            if (resp.statusCode() / 100 != 2) {
                EcsLogger.warn("com.artipie.auth")
                    .message("Okta userinfo endpoint failed")
                    .eventCategory("authentication")
                    .eventAction("userinfo")
                    .eventOutcome("failure")
                    .field("user.name", username)
                    .field("http.status", resp.statusCode())
                    .field("okta.userinfo_url", this.userinfoUrl)
                    .log();
                return null;
            }
            final JsonObject userinfo = json(resp.body());
            EcsLogger.info("com.artipie.auth")
                .message("Okta userinfo response")
                .eventCategory("authentication")
                .eventAction("userinfo")
                .eventOutcome("success")
                .field("user.name", username)
                .field("okta.userinfo_keys", String.join(",", userinfo.keySet()))
                .log();
            return userinfo;
        } catch (final IOException | InterruptedException err) {
            EcsLogger.warn("com.artipie.auth")
                .message("Failed to fetch Okta userinfo")
                .eventCategory("authentication")
                .eventAction("userinfo")
                .eventOutcome("failure")
                .field("user.name", username)
                .error(err)
                .log();
            if (err instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static String enc(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String urlDecode(final String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static JsonObject json(final String text) {
        try (JsonReader reader = Json.createReader(new StringReader(text))) {
            return reader.readObject();
        }
    }

    public static final class OktaAuthResult {

        private final String username;

        private final String email;

        private final List<String> groups;

        public OktaAuthResult(final String username, final String email, final List<String> groups) {
            this.username = username;
            this.email = email;
            this.groups = groups == null ? Collections.emptyList() : Collections.unmodifiableList(groups);
        }

        public String username() {
            return this.username;
        }

        public String email() {
            return this.email;
        }

        public List<String> groups() {
            return this.groups;
        }
    }
}
