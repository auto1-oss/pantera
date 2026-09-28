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
import com.auto1.pantera.api.RepositoryEventBroadcaster;
import com.auto1.pantera.api.RepositoryEvents;
import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.api.perms.ApiRepositoryPermission;
import com.auto1.pantera.audit.AuditEvent;
import com.auto1.pantera.audit.AuditServiceRegistry;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.cache.FiltersCache;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.io.StringReader;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.json.JsonStructure;
import javax.json.JsonValue;

/**
 * Bulk anonymous-access policy handler for
 * {@code POST /api/v1/repositories/access-policy/bulk}.
 *
 * <p>Selector + per-flag patch: a single request can flip
 * {@code anonymous_read} / {@code anonymous_write} across a typed slice
 * of the registry (hosted / proxy / group / all), optionally narrowed
 * by an explicit name list. Each surviving repo is updated in its own
 * DB transaction (delegated to {@link CrudRepoSettings#save}); per-row
 * failures are reported in the {@code skipped} array and the rest
 * continue.
 *
 * <p>Each successful update emits one T-S04 audit row tagged with a
 * shared {@code bulk_request_id} so SOC2 review can reconstruct the
 * operator's intent as a single decision.
 *
 * @since 2.2.0
 * @checkstyle ClassDataAbstractionCouplingCheck (300 lines)
 */
public final class BulkAccessPolicyHandler {

    /**
     * JSON key for the repo section in a repo config.
     */
    private static final String REPO = "repo";

    /**
     * JSON key for the anonymous-read flag.
     */
    private static final String AREAD = "anonymous_read";

    /**
     * JSON key for the anonymous-write flag.
     */
    private static final String AWRITE = "anonymous_write";

    /**
     * Selector type — hosted repos (no remotes, not group).
     */
    private static final String TYPE_HOSTED = "hosted";

    /**
     * Selector type — proxy repos (type ends with -proxy).
     */
    private static final String TYPE_PROXY = "proxy";

    /**
     * Selector type — group repos (type ends with -group).
     */
    private static final String TYPE_GROUP = "group";

    /**
     * Selector type — all repos.
     */
    private static final String TYPE_ALL = "all";

    /**
     * Audit action verb.
     */
    private static final String AUDIT_ACTION = "REPOSITORY_ACCESS_POLICY_UPDATE";

    /**
     * Repository settings CRUD.
     */
    private final CrudRepoSettings crs;

    /**
     * Pantera security policy.
     */
    private final Policy<?> policy;

    /**
     * Pantera filters cache (for slice rebuild on update).
     */
    private final FiltersCache filtersCache;

    /**
     * Vert.x event bus — used to publish repo upsert events so
     * {@code ConfigWatchService} listeners and other in-process
     * subscribers rebuild affected slices.
     */
    private final RepositoryEventBroadcaster eventBus;

    /**
     * Ctor.
     * @param crs Repository settings CRUD
     * @param policy Security policy
     * @param filtersCache Filters cache
     * @param events Repository lifecycle event broadcaster (local bus + peers)
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public BulkAccessPolicyHandler(final CrudRepoSettings crs,
        final Policy<?> policy, final FiltersCache filtersCache,
        final RepositoryEventBroadcaster events) {
        this.crs = crs;
        this.policy = policy;
        this.filtersCache = filtersCache;
        this.eventBus = events;
    }

    /**
     * Register the bulk endpoint on the router. Same authz as the
     * single-repo PUT — {@link ApiRepositoryPermission.RepositoryAction#UPDATE}.
     *
     * @param router Vert.x router
     */
    public void register(final Router router) {
        router.post("/api/v1/repositories/access-policy/bulk")
            .handler(new AuthzHandler(this.policy,
                new ApiRepositoryPermission(
                    ApiRepositoryPermission.RepositoryAction.UPDATE)))
            .handler(this::handle);
    }

    /**
     * Handle a bulk update request.
     * @param ctx Routing context
     * @checkstyle ExecutableStatementCountCheck (80 lines)
     */
    public void handle(final RoutingContext ctx) {
        final String bodyStr = ctx.body().asString();
        if (bodyStr == null || bodyStr.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "JSON body is required");
            return;
        }
        final javax.json.JsonObject body;
        try {
            body = Json.createReader(new StringReader(bodyStr)).readObject();
        } catch (final Exception ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid JSON body");
            return;
        }
        final Selector selector = BulkAccessPolicyHandler.parseSelector(ctx, body);
        if (selector == null) {
            return;
        }
        final Boolean readOverride = BulkAccessPolicyHandler.parseFlag(
            ctx, body, BulkAccessPolicyHandler.AREAD);
        if (BulkAccessPolicyHandler.flagIsInvalid(ctx, body, BulkAccessPolicyHandler.AREAD)) {
            return;
        }
        final Boolean writeOverride = BulkAccessPolicyHandler.parseFlag(
            ctx, body, BulkAccessPolicyHandler.AWRITE);
        if (BulkAccessPolicyHandler.flagIsInvalid(ctx, body, BulkAccessPolicyHandler.AWRITE)) {
            return;
        }
        if (readOverride == null && writeOverride == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "at least one of anonymous_read or anonymous_write must be set");
            return;
        }
        final String actor = ctx.user().principal().getString(AuthTokenRest.SUB);
        final String bulkRequestId = UUID.randomUUID().toString();
        CompletableFuture.supplyAsync(
            () -> this.apply(selector, readOverride, writeOverride, actor, bulkRequestId),
            HandlerExecutor.get()
        ).whenComplete((result, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            }
        });
    }

    /**
     * Apply the bulk patch to every repo selected by the request.
     * Each repo's update is its own transaction (via {@code crs.save}); a
     * save failure on one repo lands in {@code skipped} and the rest
     * continue.
     * @param selector Parsed selector
     * @param readOverride Optional anonymous_read override
     * @param writeOverride Optional anonymous_write override
     * @param actor Authenticated principal name
     * @param bulkRequestId Shared UUID for audit correlation
     * @return Response body as a Vert.x {@link JsonObject}
     * @checkstyle ExecutableStatementCountCheck (60 lines)
     */
    private JsonObject apply(final Selector selector,
        final Boolean readOverride, final Boolean writeOverride,
        final String actor, final String bulkRequestId) {
        final JsonArray updated = new JsonArray();
        final JsonArray skipped = new JsonArray();
        final Collection<String> all = this.crs.listAll();
        for (final String name : all) {
            if (!selector.matchesName(name)) {
                continue;
            }
            final RepositoryName rname = new RepositoryName.Simple(name);
            final javax.json.JsonObject config;
            try {
                final JsonStructure raw = this.crs.value(rname);
                if (!(raw instanceof javax.json.JsonObject)) {
                    skipped.add(new JsonObject().put("name", name).put("reason", "not_found"));
                    continue;
                }
                config = (javax.json.JsonObject) raw;
            } catch (final Exception ex) {
                skipped.add(new JsonObject().put("name", name).put("reason", "not_found"));
                continue;
            }
            final javax.json.JsonObject repoSection = BulkAccessPolicyHandler.repoSection(config);
            if (repoSection == null) {
                skipped.add(new JsonObject().put("name", name).put("reason", "not_found"));
                continue;
            }
            final RepoKind kind = BulkAccessPolicyHandler.classify(repoSection);
            if (!selector.matchesKind(kind)) {
                continue;
            }
            final boolean prevRead = BulkAccessPolicyHandler.readEffective(repoSection, kind);
            final boolean prevWrite = BulkAccessPolicyHandler.writeEffective(repoSection);
            final boolean newRead = readOverride != null ? readOverride : prevRead;
            final boolean newWrite = writeOverride != null ? writeOverride : prevWrite;
            if (prevRead == newRead && prevWrite == newWrite) {
                skipped.add(new JsonObject().put("name", name).put("reason", "no_change"));
                continue;
            }
            final javax.json.JsonObject patched = BulkAccessPolicyHandler.patchConfig(
                config, repoSection, newRead, newWrite);
            try {
                this.crs.save(rname, patched, actor);
            } catch (final Exception ex) {
                skipped.add(new JsonObject()
                    .put("name", name)
                    .put("reason", "save_failed: " + ex.getMessage()));
                continue;
            }
            if (this.filtersCache != null) {
                this.filtersCache.invalidate(rname.toString());
            }
            if (this.eventBus != null) {
                this.eventBus.publish(RepositoryEvents.upsert(name));
            }
            final JsonObject prev = new JsonObject()
                .put(BulkAccessPolicyHandler.AREAD, prevRead)
                .put(BulkAccessPolicyHandler.AWRITE, prevWrite);
            final JsonObject curr = new JsonObject()
                .put(BulkAccessPolicyHandler.AREAD, newRead)
                .put(BulkAccessPolicyHandler.AWRITE, newWrite);
            updated.add(new JsonObject()
                .put("name", name)
                .put("previous", prev)
                .put("current", curr));
            BulkAccessPolicyHandler.audit(actor, name, prev, curr, bulkRequestId);
        }
        return new JsonObject().put("updated", updated).put("skipped", skipped);
    }

    /**
     * Parse the selector block. Sends a 400 and returns {@code null} on
     * any structural problem.
     * @param ctx Routing context (for error replies)
     * @param body Request body
     * @return Parsed selector or {@code null} when a 400 was sent
     */
    private static Selector parseSelector(final RoutingContext ctx,
        final javax.json.JsonObject body) {
        if (!body.containsKey("selector")
            || body.get("selector").getValueType() != JsonValue.ValueType.OBJECT) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "selector is required");
            return null;
        }
        final javax.json.JsonObject sel = body.getJsonObject("selector");
        if (!sel.containsKey("type")
            || sel.get("type").getValueType() != JsonValue.ValueType.STRING) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "selector.type is required and must be a string");
            return null;
        }
        final String type = sel.getString("type");
        if (!BulkAccessPolicyHandler.TYPE_HOSTED.equals(type)
            && !BulkAccessPolicyHandler.TYPE_PROXY.equals(type)
            && !BulkAccessPolicyHandler.TYPE_GROUP.equals(type)
            && !BulkAccessPolicyHandler.TYPE_ALL.equals(type)) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "selector.type must be one of: hosted, proxy, group, all");
            return null;
        }
        final Set<String> names;
        if (sel.containsKey("names")) {
            if (sel.get("names").getValueType() != JsonValue.ValueType.ARRAY) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "selector.names must be an array of strings");
                return null;
            }
            final javax.json.JsonArray arr = sel.getJsonArray("names");
            names = new HashSet<>();
            for (final JsonValue v : arr) {
                if (v.getValueType() != JsonValue.ValueType.STRING) {
                    ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                        "selector.names must be an array of strings");
                    return null;
                }
                names.add(((javax.json.JsonString) v).getString());
            }
        } else {
            names = null;
        }
        return new Selector(type, names);
    }

    /**
     * Extract a boolean override flag from the request body. Returns
     * {@code null} when the key is absent OR present-but-non-boolean
     * (caller uses {@link #flagIsInvalid} to distinguish).
     * @param ctx Routing context (unused — kept for symmetry)
     * @param body Request body
     * @param key Field name
     * @return Boolean value or {@code null}
     */
    @SuppressWarnings("PMD.UnusedFormalParameter")
    private static Boolean parseFlag(final RoutingContext ctx,
        final javax.json.JsonObject body, final String key) {
        if (!body.containsKey(key)) {
            return null;
        }
        final JsonValue.ValueType vt = body.get(key).getValueType();
        if (vt == JsonValue.ValueType.TRUE) {
            return Boolean.TRUE;
        }
        if (vt == JsonValue.ValueType.FALSE) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Test whether an override key is present but not a boolean. When
     * true, emits a 400.
     * @param ctx Routing context (for the error reply)
     * @param body Request body
     * @param key Field name
     * @return {@code true} when a 400 was sent
     */
    private static boolean flagIsInvalid(final RoutingContext ctx,
        final javax.json.JsonObject body, final String key) {
        if (!body.containsKey(key)) {
            return false;
        }
        final JsonValue.ValueType vt = body.get(key).getValueType();
        if (vt == JsonValue.ValueType.TRUE || vt == JsonValue.ValueType.FALSE) {
            return false;
        }
        ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
            key + " must be a boolean");
        return true;
    }

    /**
     * Locate the {@code repo} section of a config object; tolerates both
     * the wrapped {@code {"repo": {...}}} and the flat form.
     * @param config Repo config root
     * @return repo section or {@code null} when the config is malformed
     */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private static javax.json.JsonObject repoSection(final javax.json.JsonObject config) {
        // null sentinel is meaningful here — the caller distinguishes
        // "malformed config" (skip with reason=not_found) from a real
        // but empty repo section.
        if (config.containsKey(BulkAccessPolicyHandler.REPO)) {
            final JsonValue v = config.get(BulkAccessPolicyHandler.REPO);
            if (v.getValueType() != JsonValue.ValueType.OBJECT) {
                return null;
            }
            return (javax.json.JsonObject) v;
        }
        return config;
    }

    /**
     * Classify a repo by its config's repo section.
     * @param repoSection The {@code repo} subobject
     * @return Logical kind
     */
    private static RepoKind classify(final javax.json.JsonObject repoSection) {
        final String type = repoSection.getString("type", "");
        if (type.endsWith("-group")) {
            return RepoKind.GROUP;
        }
        if (type.endsWith("-proxy")) {
            return RepoKind.PROXY;
        }
        // hosted: no remotes (or empty), and not a group type
        final boolean hasRemotes = repoSection.containsKey("remotes")
            && repoSection.get("remotes").getValueType() == JsonValue.ValueType.ARRAY
            && !repoSection.getJsonArray("remotes").isEmpty();
        if (hasRemotes) {
            // proxy-shaped by virtue of having remotes, even if type does
            // not end with -proxy. Treat as proxy for default purposes.
            return RepoKind.PROXY;
        }
        return RepoKind.HOSTED;
    }

    /**
     * Effective anonymous_read for a repo — explicit value if set,
     * otherwise the kind-derived default.
     * @param repoSection repo subobject
     * @param kind Logical kind
     * @return Effective value
     */
    private static boolean readEffective(final javax.json.JsonObject repoSection,
        final RepoKind kind) {
        if (repoSection.containsKey(BulkAccessPolicyHandler.AREAD)) {
            final JsonValue.ValueType vt =
                repoSection.get(BulkAccessPolicyHandler.AREAD).getValueType();
            if (vt == JsonValue.ValueType.TRUE) {
                return true;
            }
            if (vt == JsonValue.ValueType.FALSE) {
                return false;
            }
            // String "true"/"false" tolerated — matches RepositorySlices.anonymousPolicy
            if (vt == JsonValue.ValueType.STRING) {
                return Boolean.parseBoolean(
                    ((javax.json.JsonString) repoSection.get(
                        BulkAccessPolicyHandler.AREAD)).getString());
            }
        }
        return kind != RepoKind.HOSTED;
    }

    /**
     * Effective anonymous_write — explicit value if set, false otherwise.
     * @param repoSection repo subobject
     * @return Effective value
     */
    private static boolean writeEffective(final javax.json.JsonObject repoSection) {
        if (repoSection.containsKey(BulkAccessPolicyHandler.AWRITE)) {
            final JsonValue.ValueType vt =
                repoSection.get(BulkAccessPolicyHandler.AWRITE).getValueType();
            if (vt == JsonValue.ValueType.TRUE) {
                return true;
            }
            if (vt == JsonValue.ValueType.FALSE) {
                return false;
            }
            if (vt == JsonValue.ValueType.STRING) {
                return Boolean.parseBoolean(
                    ((javax.json.JsonString) repoSection.get(
                        BulkAccessPolicyHandler.AWRITE)).getString());
            }
        }
        return false;
    }

    /**
     * Rebuild the config object with the new anonymous flags replacing
     * any existing values. Always emits both flags so the persisted form
     * is explicit (matches the prior plan: emit both, never leak the
     * type-derived default into storage).
     * @param config Original full config (may have outer wrapper)
     * @param repoSection Pre-extracted repo subobject (from the same config)
     * @param newRead New value
     * @param newWrite New value
     * @return New config with both flags set on the repo section
     */
    private static javax.json.JsonObject patchConfig(
        final javax.json.JsonObject config,
        final javax.json.JsonObject repoSection,
        final boolean newRead, final boolean newWrite) {
        final JsonObjectBuilder repoBuilder = Json.createObjectBuilder();
        for (final Map.Entry<String, JsonValue> e : repoSection.entrySet()) {
            if (BulkAccessPolicyHandler.AREAD.equals(e.getKey())
                || BulkAccessPolicyHandler.AWRITE.equals(e.getKey())) {
                continue;
            }
            repoBuilder.add(e.getKey(), e.getValue());
        }
        repoBuilder.add(BulkAccessPolicyHandler.AREAD, newRead);
        repoBuilder.add(BulkAccessPolicyHandler.AWRITE, newWrite);
        final javax.json.JsonObject newRepo = repoBuilder.build();
        if (config.containsKey(BulkAccessPolicyHandler.REPO)) {
            final JsonObjectBuilder outer = Json.createObjectBuilder();
            for (final Map.Entry<String, JsonValue> e : config.entrySet()) {
                if (BulkAccessPolicyHandler.REPO.equals(e.getKey())) {
                    continue;
                }
                outer.add(e.getKey(), e.getValue());
            }
            outer.add(BulkAccessPolicyHandler.REPO, newRepo);
            return outer.build();
        }
        return newRepo;
    }

    /**
     * Emit one T-S04 audit row for an updated repo.
     * @param actor Authenticated principal
     * @param name Repository name
     * @param previous Previous flags
     * @param current New flags
     * @param bulkRequestId Shared correlation id
     */
    private static void audit(final String actor, final String name,
        final JsonObject previous, final JsonObject current,
        final String bulkRequestId) {
        final String clientIp = org.slf4j.MDC.get(
            com.auto1.pantera.http.log.EcsMdc.CLIENT_IP
        );
        final Map<String, Object> before = Map.of(
            BulkAccessPolicyHandler.AREAD,
                previous.getBoolean(BulkAccessPolicyHandler.AREAD),
            BulkAccessPolicyHandler.AWRITE,
                previous.getBoolean(BulkAccessPolicyHandler.AWRITE)
        );
        final Map<String, Object> after = Map.of(
            BulkAccessPolicyHandler.AREAD,
                current.getBoolean(BulkAccessPolicyHandler.AREAD),
            BulkAccessPolicyHandler.AWRITE,
                current.getBoolean(BulkAccessPolicyHandler.AWRITE)
        );
        final Map<String, Object> details = Map.of(
            "before", before,
            "after", after,
            "bulk_request_id", bulkRequestId
        );
        AuditServiceRegistry.instance().sharedService().record(
            new AuditEvent(
                Instant.now(), actor, BulkAccessPolicyHandler.AUDIT_ACTION,
                name, details, true, clientIp
            )
        );
    }

    /**
     * Logical repository kind.
     */
    private enum RepoKind { HOSTED, PROXY, GROUP }

    /**
     * Parsed selector — type + optional explicit names. {@code names ==
     * null} means "no name filter". Empty list also means "no filter"
     * (treat the absence and the empty list symmetrically — operators
     * frequently send {@code []} when they mean "I haven't picked any").
     */
    private static final class Selector {
        /** Selector type. */
        private final String type;
        /** Optional explicit name list. */
        private final Set<String> names;

        /**
         * Ctor.
         * @param type Selector type
         * @param names Optional explicit names
         */
        Selector(final String type, final Set<String> names) {
            this.type = type;
            this.names = names;
        }

        /**
         * Test the explicit-names filter only (cheap pre-check).
         * @param name Repo name
         * @return {@code true} when this name is included
         */
        boolean matchesName(final String name) {
            return this.names == null || this.names.isEmpty() || this.names.contains(name);
        }

        /**
         * Test the type filter.
         * @param kind Logical kind of the candidate repo
         * @return {@code true} when the selector accepts this kind
         */
        boolean matchesKind(final RepoKind kind) {
            switch (this.type) {
                case BulkAccessPolicyHandler.TYPE_HOSTED:
                    return kind == RepoKind.HOSTED;
                case BulkAccessPolicyHandler.TYPE_PROXY:
                    return kind == RepoKind.PROXY;
                case BulkAccessPolicyHandler.TYPE_GROUP:
                    return kind == RepoKind.GROUP;
                case BulkAccessPolicyHandler.TYPE_ALL:
                    return true;
                default:
                    return false;
            }
        }
    }

}
