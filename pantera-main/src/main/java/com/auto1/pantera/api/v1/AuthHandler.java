/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api.v1;

import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.api.perms.ApiAliasPermission;
import com.auto1.pantera.api.perms.ApiCooldownPermission;
import com.auto1.pantera.api.perms.ApiRepositoryPermission;
import com.auto1.pantera.api.perms.ApiRolePermission;
import com.auto1.pantera.api.perms.ApiSearchPermission;
import com.auto1.pantera.api.perms.ApiUserPermission;
import com.auto1.pantera.auth.JwtTokens;
import com.auto1.pantera.auth.OktaAuthContext;
import com.auto1.pantera.db.dao.AuthProviderDao;
import com.auto1.pantera.db.dao.UserTokenDao;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.Tokens;
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

    private final Tokens tokens;
    private final Authentication auth;
    private final CrudUsers users;
    private final Policy<?> policy;
    private final AuthProviderDao providerDao;
    private final UserTokenDao tokenDao;

    public AuthHandler(final Tokens tokens, final Authentication auth,
        final CrudUsers users, final Policy<?> policy,
        final AuthProviderDao providerDao, final UserTokenDao tokenDao) {
        this.tokens = tokens;
        this.auth = auth;
        this.users = users;
        this.policy = policy;
        this.providerDao = providerDao;
        this.tokenDao = tokenDao;
    }

    public AuthHandler(final Tokens tokens, final Authentication auth,
        final CrudUsers users, final Policy<?> policy,
        final AuthProviderDao providerDao) {
        this(tokens, auth, users, policy, providerDao, null);
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
        ctx.vertx().<Optional<AuthUser>>executeBlocking(
            () -> {
                OktaAuthContext.setMfaCode(mfa);
                try {
                    return this.auth.user(name, pass);
                } finally {
                    OktaAuthContext.clear();
                }
            },
            false
        ).onComplete(ar -> {
            if (ar.succeeded()) {
                final Optional<AuthUser> user = ar.result();
                if (user.isPresent()) {
                    final String token = this.tokens.generate(user.get());
                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("token", token).encode());
                } else {
                    ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Invalid credentials");
                }
            } else {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", "Authentication failed");
            }
        });
    }

    /**
     * GET /api/v1/auth/providers — list auth providers.
     * @param ctx Routing context
     */
    private void providersEndpoint(final RoutingContext ctx) {
        final JsonArray providers = new JsonArray();
        // Always include artipie (username/password) provider
        providers.add(
            new JsonObject()
                .put("type", "artipie")
                .put("enabled", true)
        );
        // Add SSO providers from the database
        if (this.providerDao != null) {
            for (final javax.json.JsonObject prov : this.providerDao.list()) {
                final String type = prov.getString("type", "");
                // Skip artipie and jwt-password — they're not SSO providers
                if (!"artipie".equals(type) && !"jwt-password".equals(type)) {
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
        ctx.vertx().<JsonObject>executeBlocking(
            () -> {
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
            },
            false
        ).onSuccess(result -> {
            if (result == null) {
                ApiResponse.sendError(ctx, 404, "NOT_FOUND",
                    String.format("Provider '%s' not found", name));
            } else if (result.containsKey("error")) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST", result.getString("error"));
            } else {
                ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            }
        }).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
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
        ctx.vertx().<String>executeBlocking(
            () -> {
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
                final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", "Basic " + basic)
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();
                final HttpResponse<String> resp;
                try {
                    resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (final Exception ex) {
                    throw new IllegalStateException("Token exchange failed: " + ex.getMessage(), ex);
                }
                if (resp.statusCode() / 100 != 2) {
                    EcsLogger.error("com.auto1.pantera.api.v1")
                        .message("SSO token exchange failed")
                        .eventCategory("authentication")
                        .eventAction("sso_callback")
                        .eventOutcome("failure")
                        .field("http.response.status_code", resp.statusCode())
                        .field("provider", provider)
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
                        .message("SSO id_token has no groups claim")
                        .eventCategory("authentication")
                        .eventAction("sso_groups")
                        .field("user.name", username)
                        .field("groups.claim", groupsClaim)
                        .field("claims.keys", String.join(",", claims.keySet()))
                        .log();
                }
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("SSO groups extracted from id_token")
                    .eventCategory("authentication")
                    .eventAction("sso_groups")
                    .field("user.name", username)
                    .field("groups.claim", groupsClaim)
                    .field("groups.found", String.join(",", groups))
                    .field("groups.count", groups.size())
                    .log();
                // Map Okta/IdP groups to Pantera roles using group-roles config.
                // Groups with an explicit mapping use the mapped role name.
                // Groups without a mapping use the group name as the role name
                // (auto-created in DB if it doesn't exist).
                //
                // group-roles in YAML can be either:
                //   a) An array of single-key mappings:
                //        - artipie_readers: reader
                //        - artipie_admins: admin
                //   b) A nested object (legacy):
                //        artipie_readers: reader
                //        artipie_admins: admin
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
                        .message("SSO group-roles mapping from config")
                        .eventCategory("authentication")
                        .eventAction("sso_role_mapping")
                        .field("user.name", username)
                        .field("group-roles.keys",
                            String.join(",", groupRolesMap.keySet()))
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
                        .message("SSO group mapped to role")
                        .eventCategory("authentication")
                        .eventAction("sso_role_mapping")
                        .field("user.name", username)
                        .field("okta.group", grp)
                        .field("pantera.role", mapped)
                        .field("mapping",
                            groupRolesMap.containsKey(grp) ? "explicit" : "auto")
                        .log();
                }
                // If no roles mapped, apply default role "reader" if configured
                if (roles.isEmpty()) {
                    final String defaultRole = config.getString("default-role", "reader");
                    if (defaultRole != null && !defaultRole.isEmpty()) {
                        roles.add(defaultRole);
                        EcsLogger.info("com.auto1.pantera.api.v1")
                            .message("SSO using default role (no group match)")
                            .eventCategory("authentication")
                            .eventAction("sso_role_mapping")
                            .field("user.name", username)
                            .field("default.role", defaultRole)
                            .log();
                    }
                }
                // Provision user in the database/storage
                if (AuthHandler.this.users != null) {
                    final javax.json.JsonArrayBuilder rolesArr = Json.createArrayBuilder();
                    for (final String role : roles) {
                        rolesArr.add(role);
                    }
                    final javax.json.JsonObjectBuilder userInfo = Json.createObjectBuilder()
                        .add("type", type)
                        .add("roles", rolesArr.build());
                    if (email != null && !email.isEmpty()) {
                        userInfo.add("email", email);
                    }
                    EcsLogger.info("com.auto1.pantera.api.v1")
                        .message("SSO provisioning user with roles")
                        .eventCategory("authentication")
                        .eventAction("sso_provision")
                        .field("user.name", username)
                        .field("provider", provider)
                        .field("roles", String.join(",", roles))
                        .field("roles.count", roles.size())
                        .log();
                    AuthHandler.this.users.addOrUpdate(userInfo.build(), username);
                } else {
                    EcsLogger.warn("com.auto1.pantera.api.v1")
                        .message("SSO cannot provision user - users store is null")
                        .eventCategory("authentication")
                        .eventAction("sso_provision")
                        .field("user.name", username)
                        .log();
                }
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("SSO authentication successful")
                    .eventCategory("authentication")
                    .eventAction("sso_callback")
                    .eventOutcome("success")
                    .field("user.name", username)
                    .field("provider", provider)
                    .field("groups", String.join(",", groups))
                    .field("roles", String.join(",", roles))
                    .log();
                // Generate Pantera JWT
                final AuthUser authUser = new AuthUser(username, provider);
                return AuthHandler.this.tokens.generate(authUser);
            },
            false
        ).onSuccess(token -> ctx.response().setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("token", token).encode())
        ).onFailure(err -> {
            final String msg = err.getMessage() != null ? err.getMessage() : "SSO callback failed";
            if (msg.contains("not found")) {
                ApiResponse.sendError(ctx, 404, "NOT_FOUND", msg);
            } else {
                ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", msg);
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
            return null;
        }
        for (final javax.json.JsonObject prov : this.providerDao.list()) {
            if (name.equals(prov.getString("type", ""))) {
                return prov;
            }
        }
        return null;
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
        final int expiryDays = body != null
            ? body.getInteger("expiry_days", DEFAULT_EXPIRY_DAYS)
            : DEFAULT_EXPIRY_DAYS;
        final String sub = ctx.user().principal().getString(AuthTokenRest.SUB);
        final String context = ctx.user().principal().getString(
            AuthTokenRest.CONTEXT, "artipie"
        );
        final AuthUser authUser = new AuthUser(sub, context);
        final int expirySecs = expiryDays > 0 ? expiryDays * 86400 : 0;
        final Instant expiresAt = expiryDays > 0
            ? Instant.now().plusSeconds(expirySecs) : null;
        final UUID jti = UUID.randomUUID();
        final String token;
        if (this.tokens instanceof JwtTokens) {
            token = ((JwtTokens) this.tokens).generate(authUser, expirySecs, jti);
        } else {
            token = expiryDays <= 0
                ? this.tokens.generate(authUser, true)
                : this.tokens.generate(authUser);
        }
        if (this.tokenDao != null) {
            this.tokenDao.store(jti, sub, label, token, expiresAt);
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
                AuthHandler.allowedActions(perms, "repo",
                    new String[]{"read", "create", "update", "delete", "move"},
                    new ApiRepositoryPermission[]{
                        new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ),
                        new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.CREATE),
                        new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.UPDATE),
                        new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.DELETE),
                        new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.MOVE),
                    }))
            .put("api_user_permissions",
                AuthHandler.allowedActions(perms, "user",
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
                AuthHandler.allowedActions(perms, "role",
                    new String[]{"read", "create", "update", "delete", "enable"},
                    new ApiRolePermission[]{
                        new ApiRolePermission(ApiRolePermission.RoleAction.READ),
                        new ApiRolePermission(ApiRolePermission.RoleAction.CREATE),
                        new ApiRolePermission(ApiRolePermission.RoleAction.UPDATE),
                        new ApiRolePermission(ApiRolePermission.RoleAction.DELETE),
                        new ApiRolePermission(ApiRolePermission.RoleAction.ENABLE),
                    }))
            .put("api_alias_permissions",
                AuthHandler.allowedActions(perms, "alias",
                    new String[]{"read", "create", "delete"},
                    new ApiAliasPermission[]{
                        new ApiAliasPermission(ApiAliasPermission.AliasAction.READ),
                        new ApiAliasPermission(ApiAliasPermission.AliasAction.CREATE),
                        new ApiAliasPermission(ApiAliasPermission.AliasAction.DELETE),
                    }))
            .put("api_cooldown_permissions",
                AuthHandler.allowedActions(perms, "cooldown",
                    new String[]{"read", "write"},
                    new java.security.Permission[]{
                        ApiCooldownPermission.READ,
                        ApiCooldownPermission.WRITE,
                    }))
            .put("api_search_permissions",
                AuthHandler.allowedActions(perms, "search",
                    new String[]{"read", "write"},
                    new java.security.Permission[]{
                        ApiSearchPermission.READ,
                        ApiSearchPermission.WRITE,
                    }))
            .put("can_delete_artifacts",
                perms.implies(new AdapterBasicPermission("*", "delete")));
        final JsonObject result = new JsonObject()
            .put("name", sub)
            .put("context", context != null ? context : "artipie")
            .put("permissions", permissions);
        if (this.users != null) {
            final Optional<javax.json.JsonObject> userInfo = this.users.get(sub);
            if (userInfo.isPresent()) {
                final javax.json.JsonObject info = userInfo.get();
                if (info.containsKey("email")) {
                    result.put("email", info.getString("email"));
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
            }
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
        ctx.vertx().<JsonArray>executeBlocking(
            () -> {
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
            },
            false
        ).onSuccess(
            arr -> ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("tokens", arr).encode())
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
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
        ctx.vertx().<Boolean>executeBlocking(
            () -> this.tokenDao.revoke(id, sub),
            false
        ).onSuccess(revoked -> {
            if (revoked) {
                ctx.response().setStatusCode(204).end();
            } else {
                ApiResponse.sendError(ctx, 404, "NOT_FOUND", "Token not found");
            }
        }).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    /**
     * Build a JsonArray of allowed action names by checking each permission.
     * Returns empty array if user has no permissions of this type.
     * @param perms User permission collection
     * @param type Permission type label (for logging, unused)
     * @param names Action name strings
     * @param checks Permission objects to check
     * @return JsonArray of allowed action names
     */
    private static JsonArray allowedActions(final PermissionCollection perms,
        final String type, final String[] names,
        final java.security.Permission[] checks) {
        final JsonArray result = new JsonArray();
        for (int idx = 0; idx < names.length; idx++) {
            if (perms.implies(checks[idx])) {
                result.add(names[idx]);
            }
        }
        return result;
    }
}
