/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.api.v1;

import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.api.perms.ApiAliasPermission;
import com.auto1.pantera.api.perms.ApiCooldownHistoryPermission;
import com.auto1.pantera.api.perms.ApiCooldownPermission;
import com.auto1.pantera.api.perms.ApiRepositoryPermission;
import com.auto1.pantera.api.perms.ApiRolePermission;
import com.auto1.pantera.api.perms.ApiSearchPermission;
import com.auto1.pantera.api.perms.ApiUserPermission;
import com.auto1.pantera.auth.JwtTokens;
import com.auto1.pantera.auth.OktaAuthContext;
import com.auto1.pantera.db.dao.AuthProviderDao;
import com.auto1.pantera.db.dao.AuthSettingsDao;
import com.auto1.pantera.db.dao.UserTokenDao;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.users.CrudUsers;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import java.security.PermissionCollection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonString;
import java.util.stream.Collectors;

/**
 * Auth handler for /api/v1/auth/* endpoints.
 */
public final class AuthHandler {

    /**
     * Default token expiry: 30 days in seconds.
     */
    private static final int DEFAULT_EXPIRY_DAYS = 30;

    /**
     * Auth settings key for maximum API token expiry in days.
     */
    private static final String SETTING_MAX_TOKEN_DAYS = "max_api_token_days";

    private final Tokens tokens;
    private final Authentication auth;
    private final CrudUsers users;
    private final Policy<?> policy;
    private final AuthProviderDao providerDao;
    private final UserTokenDao tokenDao;
    private final AuthSettingsDao settingsDao;

    public AuthHandler(final Tokens tokens, final Authentication auth,
        final CrudUsers users, final Policy<?> policy,
        final AuthProviderDao providerDao, final UserTokenDao tokenDao,
        final AuthSettingsDao settingsDao) {
        this.tokens = tokens;
        this.auth = auth;
        this.users = users;
        this.policy = policy;
        this.providerDao = providerDao;
        this.tokenDao = tokenDao;
        this.settingsDao = settingsDao;
    }

    public AuthHandler(final Tokens tokens, final Authentication auth,
        final CrudUsers users, final Policy<?> policy,
        final AuthProviderDao providerDao, final UserTokenDao tokenDao) {
        this(tokens, auth, users, policy, providerDao, tokenDao, null);
    }

    public AuthHandler(final Tokens tokens, final Authentication auth,
        final CrudUsers users, final Policy<?> policy,
        final AuthProviderDao providerDao) {
        this(tokens, auth, users, policy, providerDao, null, null);
    }

    /**
     * Register public auth routes (before JWT filter).
     * @param router Router
     */
    public void register(final Router router) {
        router.post("/api/v1/auth/token").handler(this::tokenEndpoint);
        router.get("/api/v1/auth/providers").handler(this::providersEndpoint);
        router.get("/api/v1/auth/providers/:name/redirect").handler(this::redirectEndpoint);
        router.post("/api/v1/auth/callback").handler(this::callbackEndpoint);
    }

    /**
     * Register protected auth routes (after JWT filter).
     * @param router Router
     */
    public void registerProtected(final Router router) {
        router.get("/api/v1/auth/me").handler(this::meEndpoint);
        router.post("/api/v1/auth/token/generate").handler(this::generateTokenEndpoint);
        router.get("/api/v1/auth/tokens").handler(this::listTokensEndpoint);
        router.delete("/api/v1/auth/tokens/:tokenId").handler(this::revokeTokenEndpoint);
        router.post("/api/v1/auth/refresh").handler(this::refreshEndpoint);
    }

    /**
     * POST /api/v1/auth/token — login endpoint, returns a session JWT.
     * Does NOT store in user_tokens — session tokens are ephemeral.
     * Explicit API tokens are created via /auth/token/generate.
     * @param ctx Routing context
     */
    private void tokenEndpoint(final RoutingContext ctx) {
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Request body is required");
            return;
        }
        final String name = body.getString("name");
        final String pass = body.getString("pass");
        final String mfa = body.getString("mfa_code");
        CompletableFuture.supplyAsync(
            (java.util.function.Supplier<Optional<AuthUser>>) () -> {
                // Also set user.name in MDC so logs from inside the
                // auth chain (AuthFromDb, Keycloak, etc.) can reference
                // who is attempting to log in.
                org.slf4j.MDC.put(
                    com.auto1.pantera.http.log.EcsMdc.USER_NAME, name
                );
                OktaAuthContext.setMfaCode(mfa);
                try {
                    return this.auth.user(name, pass);
                } finally {
                    OktaAuthContext.clear();
                }
            },
            HandlerExecutor.get()
        ).whenComplete((user, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR",
                    "Sign-in is temporarily unavailable. Please try again.");
            } else if (user.isPresent()) {
                final Tokens.TokenPair pair = this.tokens.generatePair(user.get());
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("token", pair.accessToken())
                        .put("refresh_token", pair.refreshToken())
                        .put("expires_in", pair.expiresIn())
                        .encode());
            } else {
                // Generic message — never disclose whether the user
                // exists, the password is wrong, or MFA failed. Detail
                // is in the server logs from the auth chain.
                ApiResponse.sendError(ctx, 401, "UNAUTHORIZED",
                    "Sign-in failed. Check your credentials and try again.");
            }
        });
    }

    /**
     * GET /api/v1/auth/providers — list auth providers.
     * @param ctx Routing context
     */
    private void providersEndpoint(final RoutingContext ctx) {
        final JsonArray providers = new JsonArray();
        // Always include local (username/password) provider
        providers.add(
            new JsonObject()
                .put("type", "local")
                .put("enabled", true)
        );
        // Add SSO providers from the database
        if (this.providerDao != null) {
            for (final javax.json.JsonObject prov : this.providerDao.list()) {
                final String type = prov.getString("type", "");
                // Skip local and jwt-password — they're not SSO providers
                if (!"local".equals(type) && !"jwt-password".equals(type)) {
                    providers.add(
                        new JsonObject()
                            .put("type", type)
                            .put("enabled", prov.getBoolean("enabled", true))
                    );
                }
            }
        }
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("providers", providers).encode());
    }

    /**
     * GET /api/v1/auth/providers/:name/redirect — build OAuth authorize URL.
     * @param ctx Routing context
     */
    private void redirectEndpoint(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        final String callbackUrl = ctx.queryParam("callback_url").stream()
            .findFirst().orElse(null);
        if (callbackUrl == null || callbackUrl.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "Query parameter 'callback_url' is required");
            return;
        }
        if (this.providerDao == null) {
            ApiResponse.sendError(ctx, 404, "NOT_FOUND", "No auth providers configured");
            return;
        }
        CompletableFuture.supplyAsync((java.util.function.Supplier<JsonObject>) () -> {
            final javax.json.JsonObject provider = findProvider(name);
            if (provider == null) {
                return null;
            }
            final javax.json.JsonObject config = provider.getJsonObject("config");
            final String type = provider.getString("type", "");
            final String state = Long.toHexString(
                Double.doubleToLongBits(Math.random())
            ) + Long.toHexString(System.nanoTime());
            final String authorizeUrl;
            final String clientId;
            final String scope;
            if ("okta".equals(type)) {
                final String issuer = config.getString("issuer", "");
                clientId = config.getString("client-id", "");
                scope = config.getString("scope", "openid profile");
                final String base = issuer.endsWith("/")
                    ? issuer.substring(0, issuer.length() - 1) : issuer;
                final String oidcBase = base.contains("/oauth2") ? base : base + "/oauth2";
                authorizeUrl = oidcBase + "/v1/authorize";
            } else if ("keycloak".equals(type)) {
                final String url = config.getString("url", "");
                final String realm = config.getString("realm", "");
                clientId = config.getString("client-id", "");
                scope = "openid profile";
                final String base = url.endsWith("/")
                    ? url.substring(0, url.length() - 1) : url;
                authorizeUrl = base + "/realms/" + realm
                    + "/protocol/openid-connect/auth";
            } else {
                return new JsonObject().put("error", "Unsupported provider type: " + type);
            }
            final String url = authorizeUrl
                + "?client_id=" + enc(clientId)
                + "&response_type=code"
                + "&scope=" + enc(scope)
                + "&redirect_uri=" + enc(callbackUrl)
                + "&state=" + enc(state);
            return new JsonObject().put("url", url).put("state", state);
        }, HandlerExecutor.get()).whenComplete((result, err) -> {
            if (err != null) {
                EcsLogger.error("com.auto1.pantera.api.v1")
                    .message("SSO redirect failed: "
                        + (err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName()))
                    .eventCategory("authentication")
                    .eventAction("sso_redirect")
                    .error(err)
                    .log();
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR",
                    "Sign-in is temporarily unavailable. Please try again.");
            } else if (result == null) {
                ApiResponse.sendError(ctx, 404, "NOT_FOUND",
                    "Sign-in provider is not configured.");
            } else if (result.containsKey("error")) {
                EcsLogger.warn("com.auto1.pantera.api.v1")
                    .message("SSO redirect rejected: " + result.getString("error"))
                    .eventCategory("authentication")
                    .eventAction("sso_redirect")
                    .log();
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "Sign-in provider is not configured correctly.");
            } else {
                ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            }
        });
    }

    /**
     * POST /api/v1/auth/callback — exchange OAuth code for Pantera JWT.
     * @param ctx Routing context
     */
    private void callbackEndpoint(final RoutingContext ctx) {
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Request body is required");
            return;
        }
        final String code = body.getString("code");
        final String provider = body.getString("provider");
        final String callbackUrl = body.getString("callback_url");
        if (code == null || code.isBlank() || provider == null || provider.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "Fields 'code' and 'provider' are required");
            return;
        }
        if (callbackUrl == null || callbackUrl.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "Field 'callback_url' is required");
            return;
        }
        CompletableFuture.supplyAsync(
            (java.util.function.Supplier<Tokens.TokenPair>) () -> {
                final javax.json.JsonObject prov = findProvider(provider);
                if (prov == null) {
                    throw new IllegalStateException(
                        String.format("Provider '%s' not found", provider)
                    );
                }
                final javax.json.JsonObject config = prov.getJsonObject("config");
                final String type = prov.getString("type", "");
                final String tokenUrl;
                final String clientId;
                final String clientSecret;
                if ("okta".equals(type)) {
                    final String issuer = config.getString("issuer", "");
                    clientId = config.getString("client-id", "");
                    clientSecret = config.getString("client-secret", "");
                    final String base = issuer.endsWith("/")
                        ? issuer.substring(0, issuer.length() - 1) : issuer;
                    final String oidcBase = base.contains("/oauth2") ? base : base + "/oauth2";
                    tokenUrl = oidcBase + "/v1/token";
                } else if ("keycloak".equals(type)) {
                    final String url = config.getString("url", "");
                    final String realm = config.getString("realm", "");
                    clientId = config.getString("client-id", "");
                    clientSecret = config.getString("client-password",
                        config.getString("client-secret", ""));
                    final String base = url.endsWith("/")
                        ? url.substring(0, url.length() - 1) : url;
                    tokenUrl = base + "/realms/" + realm
                        + "/protocol/openid-connect/token";
                } else {
                    throw new IllegalStateException("Unsupported provider type: " + type);
                }
                // Exchange code for tokens
                final String formBody = "grant_type=authorization_code"
                    + "&code=" + enc(code)
                    + "&redirect_uri=" + enc(callbackUrl);
                final String basic = Base64.getEncoder().encodeToString(
                    (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)
                );
                final HttpClient http = HttpClient.newHttpClient();
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", "Basic " + basic)
                    .POST(HttpRequest.BodyPublishers.ofString(formBody));
                final String[] traceHdrs =
                    com.auto1.pantera.http.trace.TraceHeaders.httpClientHeaders();
                for (int i = 0; i < traceHdrs.length; i += 2) {
                    reqBuilder = reqBuilder.header(traceHdrs[i], traceHdrs[i + 1]);
                }
                final HttpRequest request = reqBuilder.build();
                final HttpResponse<String> resp;
                try {
                    resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (final Exception ex) {
                    throw new IllegalStateException("Token exchange failed: " + ex.getMessage(), ex);
                }
                if (resp.statusCode() / 100 != 2) {
                    EcsLogger.error("com.auto1.pantera.api.v1")
                        .message("SSO token exchange failed via provider=" + provider)
                        .eventCategory("authentication")
                        .eventAction("sso_callback")
                        .eventOutcome("failure")
                        .field("http.response.status_code", resp.statusCode())
                        .log();
                    throw new IllegalStateException(
                        "Token exchange failed with status " + resp.statusCode()
                    );
                }
                final javax.json.JsonObject tokenResp;
                try (javax.json.JsonReader reader = Json.createReader(
                    new StringReader(resp.body()))) {
                    tokenResp = reader.readObject();
                }
                final String idToken = tokenResp.getString("id_token", null);
                if (idToken == null) {
                    throw new IllegalStateException("No id_token in response");
                }
                // Parse id_token JWT payload for username
                final String[] parts = idToken.split("\\.");
                if (parts.length < 2) {
                    throw new IllegalStateException("Invalid id_token format");
                }
                final byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
                final javax.json.JsonObject claims;
                try (javax.json.JsonReader reader = Json.createReader(
                    new StringReader(new String(payload, StandardCharsets.UTF_8)))) {
                    claims = reader.readObject();
                }
                String username = claims.getString("preferred_username", null);
                if (username == null || username.isEmpty()) {
                    username = claims.getString("sub", null);
                }
                if (username == null || username.isEmpty()) {
                    throw new IllegalStateException("Cannot determine username from id_token");
                }
                // Case-normalize so SSO logins always reference the same DB row
                // regardless of how Okta capitalizes preferred_username.
                username = username.toLowerCase(java.util.Locale.ROOT);
                // Extract email from id_token
                final String email = claims.getString("email", null);
                // Extract groups from id_token using the configured groups-claim
                final String groupsClaim = config.getString("groups-claim", "groups");
                final List<String> groups = new ArrayList<>();
                if (claims.containsKey(groupsClaim)) {
                    final javax.json.JsonValue gval = claims.get(groupsClaim);
                    if (gval.getValueType() == javax.json.JsonValue.ValueType.ARRAY) {
                        final javax.json.JsonArray garr = claims.getJsonArray(groupsClaim);
                        for (int gi = 0; gi < garr.size(); gi++) {
                            groups.add(garr.getString(gi, ""));
                        }
                    } else if (gval.getValueType() == javax.json.JsonValue.ValueType.STRING) {
                        groups.add(claims.getString(groupsClaim));
                    }
                } else {
                    EcsLogger.warn("com.auto1.pantera.api.v1")
                        .message("SSO id_token has no groups claim, groups_claim=" + groupsClaim
                            + " claims_keys=[" + String.join(",", claims.keySet()) + "]")
                        .eventCategory("authentication")
                        .eventAction("sso_groups")
                        .field("user.name", username)
                        .log();
                }
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("SSO groups extracted from id_token: groups_claim=" + groupsClaim
                        + " groups_found=[" + String.join(",", groups) + "]"
                        + " groups_count=" + groups.size())
                    .eventCategory("authentication")
                    .eventAction("sso_groups")
                    .field("user.name", username)
                    .log();
                // ACCESS GATE 1: allowed-groups check.
                // If meta.auth.providers[*].allowed-groups is configured,
                // the user's id_token MUST carry at least one matching group,
                // otherwise the SSO login is rejected with no DB row created.
                // This is the primary control point for "who can access Pantera".
                // If allowed-groups is NOT configured, JIT provisioning is open
                // (any IdP user can log in) — preserved for backward compat,
                // but logged as a warning so admins notice.
                final List<String> allowedGroups = new ArrayList<>();
                if (config.containsKey("allowed-groups")) {
                    final javax.json.JsonValue agVal = config.get("allowed-groups");
                    if (agVal.getValueType() == javax.json.JsonValue.ValueType.ARRAY) {
                        final javax.json.JsonArray agArr = config.getJsonArray("allowed-groups");
                        for (int ai = 0; ai < agArr.size(); ai++) {
                            allowedGroups.add(agArr.getString(ai, ""));
                        }
                    }
                }
                if (!allowedGroups.isEmpty()) {
                    final boolean intersects = groups.stream().anyMatch(allowedGroups::contains);
                    if (!intersects) {
                        EcsLogger.warn("com.auto1.pantera.api.v1")
                            .message("SSO login rejected: user not in any allowed group"
                                + " (user_groups=" + String.join(",", groups)
                                + ", allowed_groups=" + String.join(",", allowedGroups) + ")")
                            .eventCategory("authentication")
                            .eventAction("sso_callback")
                            .eventOutcome("failure")
                            .field("user.name", username)
                            .log();
                        throw new IllegalStateException(
                            "Access denied: user is not in any allowed group for this provider"
                        );
                    }
                } else {
                    EcsLogger.warn("com.auto1.pantera.api.v1")
                        .message("SSO provider '" + provider + "' has no allowed-groups "
                            + "configured — any authenticated IdP user will be provisioned. "
                            + "This is insecure; configure meta.auth.providers["
                            + provider + "].allowed-groups to restrict access.")
                        .eventCategory("authentication")
                        .eventAction("sso_callback")
                        .field("user.name", username)
                        .log();
                }
                // ACCESS GATE 2: enabled check.
                // If the user already exists in the DB and is disabled, refuse
                // the login regardless of what Okta says. This lets admins
                // revoke a user's access without needing to remove them from
                // the IdP. New users (no DB row yet) pass this check.
                if (AuthHandler.this.users != null) {
                    final Optional<javax.json.JsonObject> existing =
                        AuthHandler.this.users.get(username);
                    if (existing.isPresent()
                        && !existing.get().getBoolean("enabled", true)) {
                        EcsLogger.warn("com.auto1.pantera.api.v1")
                            .message("SSO login rejected: user is disabled in Pantera")
                            .eventCategory("authentication")
                            .eventAction("sso_callback")
                            .eventOutcome("failure")
                            .field("user.name", username)
                            .log();
                        throw new IllegalStateException(
                            "Access denied: user is disabled"
                        );
                    }
                }
                // Map Okta/IdP groups to Pantera roles using group-roles config.
                // Groups with an explicit mapping use the mapped role name.
                // Groups without a mapping use the group name as the role name
                // (auto-created in DB if it doesn't exist).
                //
                // group-roles in YAML can be either:
                //   a) An array of single-key mappings:
                //        - pantera_readers: reader
                //        - pantera_admins: admin
                //   b) A nested object (legacy):
                //        pantera_readers: reader
                //        pantera_admins: admin
                final List<String> roles = new ArrayList<>();
                final java.util.Map<String, String> groupRolesMap = new java.util.HashMap<>();
                if (config.containsKey("group-roles")) {
                    final javax.json.JsonValue grVal = config.get("group-roles");
                    if (grVal.getValueType() == javax.json.JsonValue.ValueType.ARRAY) {
                        // Array of single-key objects: [{group: role}, ...]
                        final javax.json.JsonArray grArr = config.getJsonArray("group-roles");
                        for (int ai = 0; ai < grArr.size(); ai++) {
                            final javax.json.JsonObject entry = grArr.getJsonObject(ai);
                            for (final String key : entry.keySet()) {
                                groupRolesMap.put(key, entry.getString(key));
                            }
                        }
                    } else if (grVal.getValueType() == javax.json.JsonValue.ValueType.OBJECT) {
                        // Nested object: {group: role, ...}
                        final javax.json.JsonObject grObj = config.getJsonObject("group-roles");
                        for (final String key : grObj.keySet()) {
                            groupRolesMap.put(key, grObj.getString(key));
                        }
                    }
                }
                if (!groupRolesMap.isEmpty()) {
                    EcsLogger.info("com.auto1.pantera.api.v1")
                        .message("SSO group-roles mapping from config: group_roles_keys=["
                            + String.join(",", groupRolesMap.keySet()) + "]")
                        .eventCategory("authentication")
                        .eventAction("sso_role_mapping")
                        .field("user.name", username)
                        .log();
                }
                for (final String grp : groups) {
                    if (grp.isEmpty()) {
                        continue;
                    }
                    final String mapped;
                    if (groupRolesMap.containsKey(grp)) {
                        mapped = groupRolesMap.get(grp);
                    } else {
                        // No explicit mapping — use group name as role name
                        mapped = grp;
                    }
                    roles.add(mapped);
                    EcsLogger.info("com.auto1.pantera.api.v1")
                        .message("SSO group mapped to role: group=" + grp
                            + " role=" + mapped
                            + " mapping=" + (groupRolesMap.containsKey(grp) ? "explicit" : "auto"))
                        .eventCategory("authentication")
                        .eventAction("sso_role_mapping")
                        .field("user.name", username)
                        .log();
                }
                // If Okta sent groups but none mapped to a role AND there's
                // a configured default-role, fall back to that. Note: if
                // Okta sent NO groups at all, we deliberately skip default-role
                // for existing users to preserve any manually-assigned roles.
                final boolean idTokenHasGroups = !groups.isEmpty();
                if (roles.isEmpty() && idTokenHasGroups) {
                    final String defaultRole = config.getString("default-role", "reader");
                    if (defaultRole != null && !defaultRole.isEmpty()) {
                        roles.add(defaultRole);
                        EcsLogger.info("com.auto1.pantera.api.v1")
                            .message("SSO using default role (no group match): default_role=" + defaultRole)
                            .eventCategory("authentication")
                            .eventAction("sso_role_mapping")
                            .field("user.name", username)
                            .log();
                    }
                }
                // Provision user in the database/storage.
                //
                // CRITICAL: only include "roles" in the userInfo when Okta
                // actually sent a groups claim. UserDao.addOrUpdate hard-deletes
                // existing user_roles before reinserting, so passing an empty
                // or stale role list would wipe any roles a Pantera admin
                // assigned manually via the UI. By omitting the key entirely,
                // existing role assignments are preserved.
                if (AuthHandler.this.users != null) {
                    final javax.json.JsonObjectBuilder userInfo = Json.createObjectBuilder()
                        .add("type", type);
                    if (idTokenHasGroups) {
                        final javax.json.JsonArrayBuilder rolesArr = Json.createArrayBuilder();
                        for (final String role : roles) {
                            rolesArr.add(role);
                        }
                        userInfo.add("roles", rolesArr.build());
                    }
                    if (email != null && !email.isEmpty()) {
                        userInfo.add("email", email);
                    }
                    EcsLogger.info("com.auto1.pantera.api.v1")
                        .message("SSO provisioning user via provider=" + provider
                            + " roles=[" + String.join(",", roles) + "]"
                            + " roles_count=" + roles.size()
                            + " preserve_existing_roles=" + (!idTokenHasGroups))
                        .eventCategory("authentication")
                        .eventAction("sso_provision")
                        .field("user.name", username)
                        .log();
                    AuthHandler.this.users.addOrUpdate(userInfo.build(), username);
                    // Invalidate the cached UserPermissions so the next /me
                    // call sees fresh role/permission data instead of waiting
                    // 3 minutes for the cache to expire.
                    if (AuthHandler.this.policy
                        instanceof com.auto1.pantera.security.policy.CachedDbPolicy) {
                        ((com.auto1.pantera.security.policy.CachedDbPolicy)
                            AuthHandler.this.policy).invalidate(username);
                    }
                } else {
                    EcsLogger.warn("com.auto1.pantera.api.v1")
                        .message("SSO cannot provision user - users store is null")
                        .eventCategory("authentication")
                        .eventAction("sso_provision")
                        .field("user.name", username)
                        .log();
                }
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("SSO authentication successful via provider=" + provider
                        + " groups=[" + String.join(",", groups) + "]"
                        + " roles=[" + String.join(",", roles) + "]")
                    .eventCategory("authentication")
                    .eventAction("sso_callback")
                    .eventOutcome("success")
                    .field("user.name", username)
                    .log();
                // Generate Pantera JWT pair
                final AuthUser authUser = new AuthUser(username, provider);
                return AuthHandler.this.tokens.generatePair(authUser);
            },
            HandlerExecutor.get()
        ).whenComplete((pair, err) -> {
            if (err == null) {
                ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("token", pair.accessToken())
                        .put("refresh_token", pair.refreshToken())
                        .put("expires_in", pair.expiresIn())
                        .encode());
            } else {
                // Detailed reason logged server-side for ops/forensics. The
                // client always gets a single generic message: revealing
                // "user is disabled" / "not in allowed group" / "token
                // exchange failed" lets attackers enumerate accounts and
                // probe IdP configuration. The only exception is a missing
                // provider (admin misconfig, not security-sensitive).
                final Throwable cause = err.getCause() != null ? err.getCause() : err;
                final String detail = cause.getMessage() != null
                    ? cause.getMessage() : "SSO callback failed";
                EcsLogger.error("com.auto1.pantera.api.v1")
                    .message("SSO callback failed: " + detail)
                    .eventCategory("authentication")
                    .eventAction("sso_callback")
                    .eventOutcome("failure")
                    .error(cause)
                    .log();
                if (detail.contains("Provider '") && detail.contains("not found")) {
                    ApiResponse.sendError(ctx, 404, "NOT_FOUND",
                        "Sign-in provider is not configured.");
                } else {
                    ApiResponse.sendError(ctx, 401, "UNAUTHORIZED",
                        "Sign-in failed. Please try again or contact your administrator.");
                }
            }
        });
    }

    /**
     * Find auth provider by type name.
     * @param name Provider type name
     * @return Provider JsonObject or null
     */
    private javax.json.JsonObject findProvider(final String name) {
        if (this.providerDao == null) {
            return null; // NOPMD ReturnEmptyCollectionRatherThanNull - JsonObject is a single record, not a collection; null signals "no provider configured"
        }
        for (final javax.json.JsonObject prov : this.providerDao.list()) {
            if (name.equals(prov.getString("type", ""))) {
                return prov;
            }
        }
        return null; // NOPMD ReturnEmptyCollectionRatherThanNull - JsonObject is a single record, not a collection; null signals "not found"
    }

    /**
     * URL-encode a value.
     * @param value Value to encode
     * @return Encoded value
     */
    private static String enc(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * POST /api/v1/auth/token/generate — generate API token for authenticated
     * user (no password required since they already have a valid JWT session).
     * Supports SSO users who have no password in the system.
     * @param ctx Routing context
     */
    private void generateTokenEndpoint(final RoutingContext ctx) {
        final JsonObject body = ctx.body().asJsonObject();
        final String label = body != null
            ? body.getString("label", "API Token") : "API Token";
        final int requestedDays = body != null
            ? body.getInteger("expiry_days", DEFAULT_EXPIRY_DAYS)
            : DEFAULT_EXPIRY_DAYS;
        // Read the admin-configured caps. Two keys exist for historical
        // reasons: api_token_max_ttl_seconds is what the UI (SettingsView
        // + /admin/auth-settings) writes; max_api_token_days is a legacy
        // key that preceded the UI. Prefer the UI key when present so
        // flipping the admin slider actually enforces at the server.
        // Without this, the UI toggle was security theatre — a user
        // could POST expiry_days=X directly and bypass the configured
        // cap, or POST expiry_days=0 to mint a permanent token even
        // when "Allow permanent API tokens" was off.
        int maxTokenDays = 0;
        boolean allowPermanent = true;
        if (this.settingsDao != null) {
            final int maxSeconds = this.settingsDao.getInt("api_token_max_ttl_seconds", 0);
            if (maxSeconds > 0) {
                maxTokenDays = maxSeconds / 86400;
            } else {
                maxTokenDays = this.settingsDao.getInt(SETTING_MAX_TOKEN_DAYS, 0);
            }
            allowPermanent = this.settingsDao.getBool("api_token_allow_permanent", true);
        }
        if (requestedDays <= 0 && !allowPermanent) {
            EcsLogger.info("com.auto1.pantera.auth")
                .message("Rejected permanent token request — disabled by admin policy")
                .eventCategory("authentication")
                .eventAction("token_generate")
                .eventOutcome("failure")
                .field("event.reason", "permanent_tokens_disabled")
                .log();
            ApiResponse.sendError(ctx, 400, "PERMANENT_TOKENS_DISABLED",
                "Permanent API tokens are disabled by administrator policy. "
                    + "Provide a positive expiry_days value.");
            return;
        }
        final int expiryDays;
        if (maxTokenDays > 0 && requestedDays > 0 && requestedDays > maxTokenDays) {
            expiryDays = maxTokenDays;
            EcsLogger.info("com.auto1.pantera.auth")
                .message("API token expiry capped by admin limit: requested=" + requestedDays
                    + " max=" + maxTokenDays)
                .eventCategory("authentication")
                .eventAction("token_generate")
                .log();
        } else {
            expiryDays = requestedDays;
        }
        final String sub = ctx.user().principal().getString(AuthTokenRest.SUB);
        final String context = ctx.user().principal().getString(
            AuthTokenRest.CONTEXT, "local"
        );
        final AuthUser authUser = new AuthUser(sub, context);
        final int expirySecs = expiryDays > 0 ? expiryDays * 86400 : 0;
        final Instant expiresAt = expiryDays > 0
            ? Instant.now().plusSeconds(expirySecs) : null;
        final UUID jti = UUID.randomUUID();
        final String token;
        if (this.tokens instanceof JwtTokens) {
            token = ((JwtTokens) this.tokens).generateApiToken(authUser, expirySecs, jti, label);
        } else {
            token = expiryDays <= 0
                ? this.tokens.generate(authUser, true)
                : this.tokens.generate(authUser);
            if (this.tokenDao != null) {
                this.tokenDao.store(jti, sub, label, token, expiresAt);
            }
        }
        final JsonObject resp = new JsonObject()
            .put("token", token)
            .put("id", jti.toString())
            .put("label", label);
        if (expiresAt != null) {
            resp.put("expires_at", expiresAt.toString());
        }
        resp.put("permanent", expiryDays <= 0);
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(resp.encode());
    }

    /**
     * POST /api/v1/auth/refresh — issue a fresh session JWT pair for the current user.
     *
     * <p>Requires a valid (non-expired) refresh JWT in the Authorization header. Extracts
     * the {@code sub} (username) and {@code context} (auth provider) from the existing
     * token and generates a new access + refresh token pair with a full fresh expiry
     * window. This prevents UI logout caused by silent JWT expiry during active sessions.
     *
     * <p>Response: {@code {"token": "<access>", "refresh_token": "<refresh>", "expires_in": N}}
     *
     * @param ctx Routing context (user principal populated by JWT filter)
     */
    private void refreshEndpoint(final RoutingContext ctx) {
        final String sub = ctx.user().principal().getString(AuthTokenRest.SUB);
        final String context = ctx.user().principal().getString(AuthTokenRest.CONTEXT);
        if (sub == null || sub.isBlank()) {
            ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "No subject in token");
            return;
        }
        final Tokens.TokenPair pair = this.tokens.generatePair(new AuthUser(sub, context));
        EcsLogger.debug("com.auto1.pantera.auth")
            .message("JWT refresh issued for user: " + sub)
            .eventCategory("authentication")
            .eventAction("token_refresh")
            .eventOutcome("success")
            .log();
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject()
                .put("token", pair.accessToken())
                .put("refresh_token", pair.refreshToken())
                .put("expires_in", pair.expiresIn())
                .encode());
    }

    /**
     * GET /api/v1/auth/me — current user info (protected).
     * @param ctx Routing context
     */
    private void meEndpoint(final RoutingContext ctx) {
        final String sub = ctx.user().principal().getString(AuthTokenRest.SUB);
        final String context = ctx.user().principal().getString(AuthTokenRest.CONTEXT);
        final AuthUser authUser = new AuthUser(sub, context);
        final PermissionCollection perms = this.policy.getPermissions(authUser);
        final JsonObject permissions = new JsonObject()
            .put("api_repository_permissions",
                AuthHandler.allowedActions(perms,
                    new String[]{"read", "create", "update", "delete", "move"},
                    new ApiRepositoryPermission[]{
                        new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ),
                        new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.CREATE),
                        new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.UPDATE),
                        new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.DELETE),
                        new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.MOVE),
                    }))
            .put("api_user_permissions",
                AuthHandler.allowedActions(perms,
                    new String[]{"read", "create", "update", "delete", "enable", "change_password"},
                    new ApiUserPermission[]{
                        new ApiUserPermission(ApiUserPermission.UserAction.READ),
                        new ApiUserPermission(ApiUserPermission.UserAction.CREATE),
                        new ApiUserPermission(ApiUserPermission.UserAction.UPDATE),
                        new ApiUserPermission(ApiUserPermission.UserAction.DELETE),
                        new ApiUserPermission(ApiUserPermission.UserAction.ENABLE),
                        new ApiUserPermission(ApiUserPermission.UserAction.CHANGE_PASSWORD),
                    }))
            .put("api_role_permissions",
                AuthHandler.allowedActions(perms,
                    new String[]{"read", "create", "update", "delete", "enable"},
                    new ApiRolePermission[]{
                        new ApiRolePermission(ApiRolePermission.RoleAction.READ),
                        new ApiRolePermission(ApiRolePermission.RoleAction.CREATE),
                        new ApiRolePermission(ApiRolePermission.RoleAction.UPDATE),
                        new ApiRolePermission(ApiRolePermission.RoleAction.DELETE),
                        new ApiRolePermission(ApiRolePermission.RoleAction.ENABLE),
                    }))
            .put("api_alias_permissions",
                AuthHandler.allowedActions(perms,
                    new String[]{"read", "create", "delete"},
                    new ApiAliasPermission[]{
                        new ApiAliasPermission(ApiAliasPermission.AliasAction.READ),
                        new ApiAliasPermission(ApiAliasPermission.AliasAction.CREATE),
                        new ApiAliasPermission(ApiAliasPermission.AliasAction.DELETE),
                    }))
            .put("api_cooldown_permissions",
                AuthHandler.allowedActions(perms,
                    new String[]{"read", "write"},
                    ApiCooldownPermission.READ,
                    ApiCooldownPermission.WRITE))
            .put("api_cooldown_history_permissions",
                AuthHandler.allowedActions(perms,
                    new String[]{"read"},
                    ApiCooldownHistoryPermission.READ))
            .put("api_search_permissions",
                AuthHandler.allowedActions(perms,
                    new String[]{"read", "write"},
                    ApiSearchPermission.READ,
                    ApiSearchPermission.WRITE))
            .put("can_delete_artifacts",
                perms.implies(new AdapterBasicPermission("*", "delete")));
        final JsonObject result = new JsonObject()
            .put("name", sub)
            .put("context", context != null ? context : "local")
            .put("permissions", permissions);
        if (this.users != null) {
            final Optional<javax.json.JsonObject> userInfo = this.users.get(sub);
            if (userInfo.isPresent()) {
                final javax.json.JsonObject info = userInfo.get();
                if (info.containsKey("email")) {
                    result.put("email", info.getString("email"));
                }
                // Force password change flag (set on the bootstrap admin user
                // and cleared by alterPassword once a complex password is set).
                if (info.containsKey("must_change_password")) {
                    result.put("must_change_password",
                        info.getBoolean("must_change_password", false));
                }
                if (info.containsKey("groups")) {
                    result.put("groups",
                        new JsonArray(
                            info.getJsonArray("groups")
                                .getValuesAs(JsonString.class)
                                .stream()
                                .map(JsonString::getString)
                                .collect(Collectors.toList())
                        )
                    );
                }
            } else {
                // Authenticated JWT but no matching DB row — this is the
                // root cause of "/me returns empty permissions". Most
                // likely a username case/format mismatch between the
                // SSO callback's preferred_username and the row created
                // earlier (or the row was deleted out from under the JWT).
                EcsLogger.warn("com.auto1.pantera.api.v1")
                    .message("/me: authenticated user has no DB row — "
                        + "permissions will be empty"
                        + " (context=" + (context != null ? context : "local") + ")")
                    .eventCategory("authentication")
                    .eventAction("me_user_lookup")
                    .eventOutcome("failure")
                    .field("user.name", sub)
                    .log();
            }
        }
        // Public-read mirror of the two auth_settings keys the token-
        // generation UI needs (max TTL + permanent toggle). The dedicated
        // GET /admin/auth-settings is admin-only, which silently 403'd for
        // read-only users and made AppHeader.vue fall back to a hardcoded
        // 30/90-day dropdown — causing the illusion of a role-based token
        // policy that does not exist server-side. Exposing the two fields
        // here lets every authenticated user see the actual options the
        // server already accepts from them. Write-path remains admin-only.
        if (this.settingsDao != null) {
            result.put("auth_settings", new JsonObject()
                .put("api_token_max_ttl_seconds",
                    this.settingsDao.get("api_token_max_ttl_seconds").orElse("31536000"))
                .put("api_token_allow_permanent",
                    this.settingsDao.get("api_token_allow_permanent").orElse("true")));
        }
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(result.encode());
    }

    /**
     * GET /api/v1/auth/tokens — list current user's API tokens (protected).
     * @param ctx Routing context
     */
    private void listTokensEndpoint(final RoutingContext ctx) {
        if (this.tokenDao == null) {
            ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("tokens", new JsonArray()).encode());
            return;
        }
        final String sub = ctx.user().principal().getString(AuthTokenRest.SUB);
        CompletableFuture.supplyAsync((java.util.function.Supplier<JsonArray>) () -> {
            final JsonArray arr = new JsonArray();
            for (final UserTokenDao.TokenInfo info : this.tokenDao.listByUser(sub)) {
                final JsonObject obj = new JsonObject()
                    .put("id", info.id().toString())
                    .put("label", info.label())
                    .put("created_at", info.createdAt().toString());
                if (info.expiresAt() != null) {
                    obj.put("expires_at", info.expiresAt().toString());
                    obj.put("expired", Instant.now().isAfter(info.expiresAt()));
                } else {
                    obj.put("permanent", true);
                }
                arr.add(obj);
            }
            return arr;
        }, HandlerExecutor.get()).whenComplete((arr, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("tokens", arr).encode());
            }
        });
    }

    /**
     * DELETE /api/v1/auth/tokens/:tokenId — revoke an API token (protected).
     * @param ctx Routing context
     */
    private void revokeTokenEndpoint(final RoutingContext ctx) {
        if (this.tokenDao == null) {
            ApiResponse.sendError(ctx, 501, "NOT_IMPLEMENTED",
                "Token management not available");
            return;
        }
        final String sub = ctx.user().principal().getString(AuthTokenRest.SUB);
        final String tokenId = ctx.pathParam("tokenId");
        final UUID id;
        try {
            id = UUID.fromString(tokenId);
        } catch (final IllegalArgumentException ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid token ID");
            return;
        }
        CompletableFuture.supplyAsync(
            () -> this.tokenDao.revoke(id, sub),
            HandlerExecutor.get()
        ).whenComplete((revoked, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else if (revoked) {
                ctx.response().setStatusCode(204).end();
            } else {
                ApiResponse.sendError(ctx, 404, "NOT_FOUND", "Token not found");
            }
        });
    }

    /**
     * Build a JsonArray of allowed action names by checking each permission.
     * Returns empty array if user has no permissions of this type.
     * @param perms User permission collection
     * @param names Action name strings
     * @param checks Permission objects to check
     * @return JsonArray of allowed action names
     */
    private static JsonArray allowedActions(final PermissionCollection perms,
        final String[] names,
        final java.security.Permission... checks) {
        final JsonArray result = new JsonArray();
        for (int idx = 0; idx < names.length; idx++) {
            if (perms.implies(checks[idx])) {
                result.add(names[idx]);
            }
        }
        return result;
    }
}
