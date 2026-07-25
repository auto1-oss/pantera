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
import com.auto1.pantera.api.AuthzHandler;
import com.auto1.pantera.api.perms.ApiAdminPermission;
import com.auto1.pantera.auth.RevocationBlocklist;
import com.auto1.pantera.cache.CacheBroadcast;
import com.auto1.pantera.db.dao.AuthSettingsDao;
import com.auto1.pantera.db.dao.PgpKeyringDao;
import com.auto1.pantera.db.dao.UserTokenDao;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.maven.security.InMemoryKeyringStore;
import com.auto1.pantera.maven.security.KeyringStoreRegistry;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Admin-only handler for auth settings management and user token revocation.
 * Registers protected endpoints under /api/v1/admin/.
 * @since 2.1.0
 */
public final class AdminAuthHandler {

    /**
     * Minimum allowed value for access_token_ttl_seconds setting.
     */
    private static final int MIN_ACCESS_TOKEN_TTL = 60;

    /**
     * TTL in seconds for user-level revocation blocklist entries (2 hours).
     */
    private static final int REVOKE_USER_TTL_SECONDS = 7200;

    /**
     * Auth settings DAO.
     */
    private final AuthSettingsDao settingsDao;

    /**
     * User token DAO.
     */
    private final UserTokenDao tokenDao;

    /**
     * Revocation blocklist for in-memory/cache invalidation.
     */
    private final RevocationBlocklist blocklist;

    /**
     * Security policy for authorization checks.
     */
    private final Policy<?> policy;

    /**
     * Cross-node cache-invalidation broadcast. Null in single-instance /
     * Valkey-less deployments — breaker/bulkhead settings changes then stay
     * local-node-only exactly as before 2.3.0 (no peers to broadcast to).
     */
    private final CacheBroadcast pubSub;

    /**
     * DAO for the {@code pgp_keyring} table (WS4-maven.3), or {@code null}
     * on a DB-less boot — but {@link AdminAuthHandler} itself is only ever
     * constructed when a DataSource is present (see {@code AsyncApiVerticle}),
     * so this is non-null on every production path.
     */
    private final PgpKeyringDao pgpKeyringDao;

    /**
     * Ctor without cross-node broadcast (single-instance deployments).
     * @param settingsDao Auth settings DAO
     * @param tokenDao User token DAO
     * @param blocklist Revocation blocklist
     * @param policy Security policy
     */
    public AdminAuthHandler(final AuthSettingsDao settingsDao,
        final UserTokenDao tokenDao, final RevocationBlocklist blocklist,
        final Policy<?> policy) {
        this(settingsDao, tokenDao, blocklist, policy, null, null);
    }

    /**
     * Ctor.
     * @param settingsDao Auth settings DAO
     * @param tokenDao User token DAO
     * @param blocklist Revocation blocklist
     * @param policy Security policy
     * @param pubSub Cross-node cache-invalidation broadcast, or {@code null}
     *     when Valkey isn't configured (WS2.3, 2.3.0)
     */
    public AdminAuthHandler(final AuthSettingsDao settingsDao,
        final UserTokenDao tokenDao, final RevocationBlocklist blocklist,
        final Policy<?> policy, final CacheBroadcast pubSub) {
        this(settingsDao, tokenDao, blocklist, policy, pubSub, null);
    }

    /**
     * Ctor with the PGP keyring DAO (WS4-maven.3). The single
     * field-initializing constructor — every other overload delegates here.
     * @param settingsDao Auth settings DAO
     * @param tokenDao User token DAO
     * @param blocklist Revocation blocklist
     * @param policy Security policy
     * @param pubSub Cross-node cache-invalidation broadcast, or {@code null}
     *     when Valkey isn't configured (WS2.3, 2.3.0)
     * @param pgpKeyringDao PGP keyring DAO
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public AdminAuthHandler(final AuthSettingsDao settingsDao,
        final UserTokenDao tokenDao, final RevocationBlocklist blocklist,
        final Policy<?> policy, final CacheBroadcast pubSub,
        final PgpKeyringDao pgpKeyringDao) {
        this.settingsDao = settingsDao;
        this.tokenDao = tokenDao;
        this.blocklist = blocklist;
        this.policy = policy;
        this.pubSub = pubSub;
        this.pgpKeyringDao = pgpKeyringDao;
    }

    /**
     * Register admin auth routes. All require JWT authentication (via global
     * filter) AND admin-level authorization (ApiUserPermission.DELETE).
     * @param router Router
     */
    public void register(final Router router) {
        final AuthzHandler adminAuthz = new AuthzHandler(
            this.policy, ApiAdminPermission.ADMIN
        );
        router.get("/api/v1/admin/auth-settings")
            .handler(adminAuthz).handler(this::getSettings);
        router.put("/api/v1/admin/auth-settings")
            .handler(adminAuthz).handler(this::updateSettings);
        router.post("/api/v1/admin/revoke-user/:username")
            .handler(adminAuthz).handler(this::revokeUser);
        router.get("/api/v1/admin/circuit-breaker-settings")
            .handler(adminAuthz).handler(this::getCircuitBreakerSettings);
        router.put("/api/v1/admin/circuit-breaker-settings")
            .handler(adminAuthz).handler(this::updateCircuitBreakerSettings);
        router.get("/api/v1/admin/upstream-breaker-settings")
            .handler(adminAuthz).handler(this::getUpstreamBreakerSettings);
        router.put("/api/v1/admin/upstream-breaker-settings")
            .handler(adminAuthz).handler(this::updateUpstreamBreakerSettings);
        router.get("/api/v1/admin/pgp-keys")
            .handler(adminAuthz).handler(this::listPgpKeys);
        router.post("/api/v1/admin/pgp-keys")
            .handler(adminAuthz).handler(this::uploadPgpKey);
        router.delete("/api/v1/admin/pgp-keys/:keyId")
            .handler(adminAuthz).handler(this::deletePgpKey);
    }

    /**
     * GET /api/v1/admin/pgp-keys — list registered keys (identity/provenance
     * only, never the armored key material).
     */
    private void listPgpKeys(final RoutingContext ctx) {
        CompletableFuture.supplyAsync(
            () -> this.pgpKeyringDao.list(), HandlerExecutor.get()
        ).whenComplete((rows, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                return;
            }
            final JsonArray keys = new JsonArray();
            for (final PgpKeyringDao.KeyRow row : rows) {
                keys.add(new JsonObject()
                    .put("key_id_hex", row.keyIdHex())
                    .put("fingerprint", row.fingerprint())
                    .put("uploaded_by", row.uploadedBy())
                    .put("uploaded_at", DateTimeFormatter.ISO_INSTANT.format(row.uploadedAt()))
                    .put("description", row.description()));
            }
            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("keys", keys).encode());
        });
    }

    /**
     * POST /api/v1/admin/pgp-keys — body {@code {"public_key_armored": "...",
     * "description": "..."}}. Parses the ASCII-armored block (rejecting
     * malformed input with 400 before any DB write), inserts one row per
     * key found (a block may contain a master key plus sub-keys — the
     * signer of a future {@code .asc} may be any of them), and evicts the
     * installed {@link KeyringStoreRegistry} cache entry for each so the
     * very next verification sees the new key without waiting for the TTL.
     */
    private void uploadPgpKey(final RoutingContext ctx) {
        final JsonObject body = ctx.body().asJsonObject();
        final String armored = body == null ? null : body.getString("public_key_armored");
        if (armored == null || armored.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "public_key_armored is required");
            return;
        }
        final String description = body.getString("description");
        final List<InMemoryKeyringStore.KeyDescriptor> descriptors;
        try {
            descriptors = InMemoryKeyringStore.describeKeys(
                armored.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
        } catch (final Exception ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "public_key_armored could not be parsed as a PGP public key: " + ex.getMessage());
            return;
        }
        if (descriptors.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "No keys found in public_key_armored");
            return;
        }
        final String owner = ctx.user() == null
            ? "admin" : ctx.user().principal().getString(AuthTokenRest.SUB);
        CompletableFuture.supplyAsync(() -> {
            final JsonArray inserted = new JsonArray();
            for (final InMemoryKeyringStore.KeyDescriptor descriptor : descriptors) {
                this.pgpKeyringDao.insert(
                    descriptor.keyIdHex(), descriptor.fingerprintHex(), armored, owner, description
                );
                KeyringStoreRegistry.invalidate(Long.parseUnsignedLong(descriptor.keyIdHex(), 16));
                inserted.add(new JsonObject()
                    .put("key_id_hex", descriptor.keyIdHex())
                    .put("fingerprint", descriptor.fingerprintHex()));
            }
            return inserted;
        }, HandlerExecutor.get()).whenComplete((inserted, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                return;
            }
            EcsLogger.info("com.auto1.pantera.api.v1")
                .message("Admin uploaded PGP key(s) (" + inserted.size() + " key id(s))")
                .eventCategory("configuration")
                .eventAction("pgp_key_upload")
                .eventOutcome("success")
                .field("log.source", "application")
                .log();
            ctx.response()
                .setStatusCode(201)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("keys", inserted).encode());
        });
    }

    /**
     * DELETE /api/v1/admin/pgp-keys/{keyId} — {@code keyId} is the 16-char
     * hex long key id. Evicts the {@link KeyringStoreRegistry} cache entry
     * so the next verification of that signer immediately sees
     * {@code UNTRUSTED_KEY}.
     */
    private void deletePgpKey(final RoutingContext ctx) {
        final String keyId = ctx.pathParam("keyId");
        if (keyId == null || keyId.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "keyId is required");
            return;
        }
        CompletableFuture.supplyAsync(
            () -> this.pgpKeyringDao.delete(keyId), HandlerExecutor.get()
        ).whenComplete((deleted, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                return;
            }
            if (!deleted) {
                ApiResponse.sendError(ctx, 404, "NOT_FOUND", "No key with id " + keyId);
                return;
            }
            try {
                KeyringStoreRegistry.invalidate(Long.parseUnsignedLong(keyId, 16));
            } catch (final NumberFormatException ignored) {
                // keyId shape is validated by the delete affecting a row above;
                // a malformed id would simply not have matched any row.
            }
            EcsLogger.info("com.auto1.pantera.api.v1")
                .message("Admin deleted PGP key key_id_hex=" + keyId)
                .eventCategory("configuration")
                .eventAction("pgp_key_delete")
                .eventOutcome("success")
                .field("log.source", "application")
                .log();
            ctx.response().setStatusCode(204).end();
        });
    }

    /**
     * Whitelist of keys the circuit-breaker endpoint accepts.
     * Anything outside this set in a PUT body is rejected with 400 —
     * prevents the endpoint from becoming a generic settings-poke hole.
     */
    private static final java.util.Set<String> CB_KEYS = java.util.Set.of(
        "circuit_breaker_failure_rate_threshold",
        "circuit_breaker_minimum_number_of_calls",
        "circuit_breaker_sliding_window_seconds",
        "circuit_breaker_initial_block_seconds",
        "circuit_breaker_max_block_seconds"
    );

    /**
     * GET /api/v1/admin/circuit-breaker-settings — returns the 5 keys
     * (with DB-persisted values; absent keys fall through to the
     * hardcoded defaults on the server side). Response always includes
     * every key so the UI form can populate without extra null checks.
     */
    private void getCircuitBreakerSettings(final RoutingContext ctx) {
        CompletableFuture.supplyAsync(() -> {
            final com.auto1.pantera.http.timeout.AutoBlockSettings current =
                com.auto1.pantera.circuit.CircuitBreakerSettingsLoader.activeSupplier().get();
            final JsonObject result = new JsonObject()
                .put("circuit_breaker_failure_rate_threshold",
                    String.valueOf(current.failureRateThreshold()))
                .put("circuit_breaker_minimum_number_of_calls",
                    String.valueOf(current.minimumNumberOfCalls()))
                .put("circuit_breaker_sliding_window_seconds",
                    String.valueOf(current.slidingWindowSeconds()))
                .put("circuit_breaker_initial_block_seconds",
                    String.valueOf(current.initialBlockDuration().toSeconds()))
                .put("circuit_breaker_max_block_seconds",
                    String.valueOf(current.maxBlockDuration().toSeconds()));
            return result;
        }, HandlerExecutor.get()).whenComplete((settings, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(settings.encode());
            }
        });
    }

    /**
     * PUT /api/v1/admin/circuit-breaker-settings — partial updates OK;
     * only keys from the {@link #CB_KEYS} whitelist are persisted.
     * Values are validated by round-tripping through the
     * {@link com.auto1.pantera.http.timeout.AutoBlockSettings} record
     * constructor — if the proposed change would produce an invariant
     * violation (rate > 1.0, negative duration, etc.) the PUT is
     * rejected and nothing is written.
     */
    private void updateCircuitBreakerSettings(final RoutingContext ctx) {
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null || body.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Request body is required");
            return;
        }
        for (final String key : body.fieldNames()) {
            if (!CB_KEYS.contains(key)) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "Unknown circuit-breaker setting: " + key);
                return;
            }
        }
        // Validate: fetch current settings, overlay the proposed changes,
        // round-trip through AutoBlockSettings constructor (which
        // enforces invariants). If that throws, reject the PUT.
        final com.auto1.pantera.http.timeout.AutoBlockSettings current =
            com.auto1.pantera.circuit.CircuitBreakerSettingsLoader.activeSupplier().get();
        try {
            new com.auto1.pantera.http.timeout.AutoBlockSettings(
                Double.parseDouble(body.getString(
                    "circuit_breaker_failure_rate_threshold",
                    String.valueOf(current.failureRateThreshold())
                )),
                Integer.parseInt(body.getString(
                    "circuit_breaker_minimum_number_of_calls",
                    String.valueOf(current.minimumNumberOfCalls())
                )),
                Integer.parseInt(body.getString(
                    "circuit_breaker_sliding_window_seconds",
                    String.valueOf(current.slidingWindowSeconds())
                )),
                java.time.Duration.ofSeconds(Integer.parseInt(body.getString(
                    "circuit_breaker_initial_block_seconds",
                    String.valueOf(current.initialBlockDuration().toSeconds())
                ))),
                java.time.Duration.ofSeconds(Integer.parseInt(body.getString(
                    "circuit_breaker_max_block_seconds",
                    String.valueOf(current.maxBlockDuration().toSeconds())
                )))
            );
        } catch (final IllegalArgumentException ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "Invalid circuit-breaker setting: " + ex.getMessage());
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            for (final String key : body.fieldNames()) {
                this.settingsDao.put(key, body.getValue(key).toString());
            }
            // Invalidate the shared loader so the next record outcome
            // across every AutoBlockRegistry picks up the new values.
            final com.auto1.pantera.circuit.CircuitBreakerSettingsLoader loader =
                com.auto1.pantera.circuit.CircuitBreakerSettingsLoader.installed();
            if (loader != null) {
                loader.invalidate();
            }
            // WS2.3 (2.3.0): broadcast so every peer's loader re-reads the
            // DB too — pre-2.3.0 this only ever invalidated the receiving
            // node, leaving peers stale until their own restart.
            if (this.pubSub != null) {
                this.pubSub.publish(
                    com.auto1.pantera.circuit.CircuitBreakerSettingsLoader.BROADCAST_CHANNEL,
                    "changed"
                );
            }
            return null;
        }, HandlerExecutor.get()).whenComplete((ignored, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("Admin updated circuit-breaker settings (keys="
                        + String.join(",", body.fieldNames()) + ")")
                    .eventCategory("configuration")
                    .eventAction("circuit_breaker_settings_update")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
                ctx.response().setStatusCode(204).end();
            }
        });
    }

    /**
     * Whitelist for the OUTBOUND http-client breaker endpoint —
     * distinct from {@link #CB_KEYS} (group-member breaker).
     */
    private static final java.util.Set<String> UB_KEYS = java.util.Set.of(
        "upstream_breaker_failure_rate_threshold",
        "upstream_breaker_minimum_calls",
        "upstream_breaker_window_seconds",
        "upstream_breaker_seed_backoff_seconds",
        "upstream_breaker_max_backoff_seconds"
    );

    /**
     * GET /api/v1/admin/upstream-breaker-settings — current values for
     * the per-upstream (scheme://host:port) outbound breaker. Always
     * returns every key so the UI form populates without null checks.
     */
    private void getUpstreamBreakerSettings(final RoutingContext ctx) {
        CompletableFuture.supplyAsync(() -> {
            final com.auto1.pantera.http.client.circuitbreaker.CircuitBreakerConfig current =
                com.auto1.pantera.circuit.UpstreamBreakerSettingsLoader.activeSupplier().get();
            return new JsonObject()
                .put("upstream_breaker_failure_rate_threshold",
                    String.valueOf(current.failureRateThreshold()))
                .put("upstream_breaker_minimum_calls",
                    String.valueOf(current.minimumCalls()))
                .put("upstream_breaker_window_seconds",
                    String.valueOf(current.windowSeconds()))
                .put("upstream_breaker_seed_backoff_seconds",
                    String.valueOf(current.seedBackoff().toSeconds()))
                .put("upstream_breaker_max_backoff_seconds",
                    String.valueOf(current.maxBackoff().toSeconds()));
        }, HandlerExecutor.get()).whenComplete((settings, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(settings.encode());
            }
        });
    }

    /**
     * PUT /api/v1/admin/upstream-breaker-settings — partial updates OK;
     * only {@link #UB_KEYS} accepted. Values validated by round-tripping
     * through the {@code CircuitBreakerConfig} record constructor before
     * anything is written.
     */
    private void updateUpstreamBreakerSettings(final RoutingContext ctx) {
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null || body.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Request body is required");
            return;
        }
        for (final String key : body.fieldNames()) {
            if (!UB_KEYS.contains(key)) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "Unknown upstream-breaker setting: " + key);
                return;
            }
        }
        final com.auto1.pantera.http.client.circuitbreaker.CircuitBreakerConfig current =
            com.auto1.pantera.circuit.UpstreamBreakerSettingsLoader.activeSupplier().get();
        try {
            new com.auto1.pantera.http.client.circuitbreaker.CircuitBreakerConfig(
                java.time.Duration.ofSeconds(Integer.parseInt(body.getString(
                    "upstream_breaker_seed_backoff_seconds",
                    String.valueOf(current.seedBackoff().toSeconds())
                ))),
                java.time.Duration.ofSeconds(Integer.parseInt(body.getString(
                    "upstream_breaker_max_backoff_seconds",
                    String.valueOf(current.maxBackoff().toSeconds())
                ))),
                current.shouldTripOnException(),
                current.shouldTripOnStatus(),
                Double.parseDouble(body.getString(
                    "upstream_breaker_failure_rate_threshold",
                    String.valueOf(current.failureRateThreshold())
                )),
                Integer.parseInt(body.getString(
                    "upstream_breaker_minimum_calls",
                    String.valueOf(current.minimumCalls())
                )),
                Integer.parseInt(body.getString(
                    "upstream_breaker_window_seconds",
                    String.valueOf(current.windowSeconds())
                ))
            );
        } catch (final IllegalArgumentException ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "Invalid upstream-breaker setting: " + ex.getMessage());
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            for (final String key : body.fieldNames()) {
                this.settingsDao.put(key, body.getValue(key).toString());
            }
            final com.auto1.pantera.circuit.UpstreamBreakerSettingsLoader loader =
                com.auto1.pantera.circuit.UpstreamBreakerSettingsLoader.installed();
            if (loader != null) {
                loader.invalidate();
            }
            // WS2.3 (2.3.0): broadcast so every peer's loader re-reads the
            // DB too — pre-2.3.0 this only ever invalidated the receiving
            // node, leaving peers stale until their own restart.
            if (this.pubSub != null) {
                this.pubSub.publish(
                    com.auto1.pantera.circuit.UpstreamBreakerSettingsLoader.BROADCAST_CHANNEL,
                    "changed"
                );
            }
            return null;
        }, HandlerExecutor.get()).whenComplete((ignored, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("Admin updated upstream-breaker settings (keys="
                        + String.join(",", body.fieldNames()) + ")")
                    .eventCategory("configuration")
                    .eventAction("upstream_breaker_settings_update")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
                ctx.response().setStatusCode(204).end();
            }
        });
    }

    /**
     * GET /api/v1/admin/auth-settings — returns all auth_settings as a JSON object.
     * @param ctx Routing context
     */
    private void getSettings(final RoutingContext ctx) {
        CompletableFuture.supplyAsync(() -> {
            final Map<String, String> all = this.settingsDao.getAll();
            final JsonObject result = new JsonObject();
            for (final Map.Entry<String, String> entry : all.entrySet()) {
                result.put(entry.getKey(), entry.getValue());
            }
            return result;
        }, HandlerExecutor.get()).whenComplete((settings, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(settings.encode());
            }
        });
    }

    /**
     * PUT /api/v1/admin/auth-settings — updates settings from JSON body.
     * Validates access_token_ttl_seconds >= 60 if present.
     * @param ctx Routing context
     */
    private void updateSettings(final RoutingContext ctx) {
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null || body.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Request body is required");
            return;
        }
        // Validate access_token_ttl_seconds if provided
        if (body.containsKey("access_token_ttl_seconds")) {
            final Object rawTtl = body.getValue("access_token_ttl_seconds");
            final int ttl;
            try {
                ttl = Integer.parseInt(rawTtl.toString());
            } catch (final NumberFormatException ex) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "access_token_ttl_seconds must be an integer");
                return;
            }
            if (ttl < MIN_ACCESS_TOKEN_TTL) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "access_token_ttl_seconds must be >= " + MIN_ACCESS_TOKEN_TTL);
                return;
            }
        }
        CompletableFuture.supplyAsync(() -> {
            for (final String key : body.fieldNames()) {
                this.settingsDao.put(key, body.getValue(key).toString());
            }
            return null;
        }, HandlerExecutor.get()).whenComplete((ignored, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                // B4: key NAMES are admin-supplied and currently safe to
                // log (operational signal — which settings changed). Pair
                // each name with =<redacted> so any future expansion that
                // accidentally pulls the VALUE in still emits a redaction
                // placeholder, not the credential.
                final StringBuilder redactedKeys = new StringBuilder();
                for (final String name : body.fieldNames()) {
                    if (redactedKeys.length() > 0) {
                        redactedKeys.append(',');
                    }
                    redactedKeys.append(name).append("=<redacted>");
                }
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("Admin updated auth settings (keys="
                        + redactedKeys + ")")
                    .eventCategory("iam")
                    .eventAction("auth_settings_update")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
                ctx.response().setStatusCode(204).end();
            }
        });
    }

    /**
     * POST /api/v1/admin/revoke-user/:username — revokes all tokens for a user in DB
     * and adds the user to the in-memory blocklist for {@value #REVOKE_USER_TTL_SECONDS} seconds.
     * @param ctx Routing context
     */
    private void revokeUser(final RoutingContext ctx) {
        final String username = ctx.pathParam("username");
        if (username == null || username.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Username is required");
            return;
        }
        CompletableFuture.supplyAsync(
            () -> this.tokenDao.revokeAllForUser(username),
            HandlerExecutor.get()
        ).whenComplete((count, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                if (this.blocklist != null) {
                    this.blocklist.revokeUser(username, REVOKE_USER_TTL_SECONDS);
                }
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("Admin revoked all tokens for user (revoked_count=" + count + ")")
                    .eventCategory("iam")
                    .eventAction("user_revoke")
                    .eventOutcome("success")
                    .field("user.name", username)
                    .field("log.source", "application")
                    .log();
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("username", username)
                        .put("revoked_count", count)
                        .encode());
            }
        });
    }
}
