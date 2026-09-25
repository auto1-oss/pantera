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
import com.auto1.pantera.api.RepoAuthzHandler;
import com.auto1.pantera.api.SecretRebindException;
import com.auto1.pantera.api.SecretRedactor;
import com.auto1.pantera.api.RepositoryEventBroadcaster;
import com.auto1.pantera.api.RepositoryEvents;
import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.api.perms.ApiRepositoryPermission;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.scheduling.MetadataEventQueues;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.RepoData;
import com.auto1.pantera.settings.cache.FiltersCache;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import com.auto1.pantera.settings.repo.FsStorageRootPolicy;
import com.auto1.pantera.settings.repo.SupportedRepoTypes;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.io.StringReader;
import java.security.PermissionCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.json.Json;
import javax.json.JsonStructure;
import javax.json.JsonValue;

/**
 * Repository handler for /api/v1/repositories/* endpoints.
 */
public final class RepositoryHandler {

    /**
     * JSON key for repo section.
     */
    private static final String REPO = "repo";

    /**
     * Local-path extraction only (no roots): which storage blocks address
     * the local filesystem.
     */
    private static final FsStorageRootPolicy LOCAL_PATHS =
        new FsStorageRootPolicy(java.util.List.of());

    /**
     * Repository types the server can serve.
     */
    private static final SupportedRepoTypes TYPES = new SupportedRepoTypes();

    /**
     * How long a repository DELETE waits for the data removal before it
     * answers 202; below the UI's 10 s request timeout and the API server's
     * 60 s idle timeout.
     */
    private static final java.time.Duration DELETE_WAIT = java.time.Duration.ofSeconds(5);

    /**
     * Repository deletes in progress on this node. Shared by every
     * AsyncApiVerticle instance (one handler per instance), so a second
     * DELETE is recognised whichever event loop receives it.
     */
    private static final RepositoryRemovals REMOVALS =
        new RepositoryRemovals(RepositoryHandler.DELETE_WAIT);

    /**
     * Pantera filters cache.
     */
    private final FiltersCache filtersCache;

    /**
     * Repository settings create/read/update/delete.
     */
    private final CrudRepoSettings crs;

    /**
     * Repository data management.
     */
    private final RepoData repoData;

    /**
     * Pantera security policy.
     */
    private final Policy<?> policy;

    /**
     * Artifact metadata events queue.
     */
    private final Optional<MetadataEventQueues> events;

    /**
     * Vert.x event bus.
     */
    private final RepositoryEventBroadcaster eventBus;

    /**
     * Approved roots for inline {@code fs} storage submitted through this
     * API (SECURITY, 2.2.9 — see {@link FsStorageRootPolicy}).
     */
    private final java.util.function.Supplier<FsStorageRootPolicy> fsRoots;

    /**
     * Outbound-URL policy for {@code remotes[].url} (SECURITY, 2.2.9 — see
     * {@link RemoteUrlPolicy}).
     */
    private final RemoteUrlPolicy remoteUrls;

    /**
     * Artifact index: a deleted repository's rows are purged with it.
     */
    private final ArtifactIndex artifactIndex;

    /**
     * Ctor.
     * @param filtersCache Pantera filters cache
     * @param crs Repository settings CRUD
     * @param repoData Repository data management
     * @param policy Pantera security policy
     * @param events Artifact events queue
     * @param cooldown Cooldown service
     * @param events2 Repository lifecycle event broadcaster (local bus + peers)
     * @param artifactIndex Artifact index (rows purged on repository delete)
     * @checkstyle ParameterNumberCheck (10 lines)
     */
    public RepositoryHandler(final FiltersCache filtersCache,
        final CrudRepoSettings crs, final RepoData repoData,
        final Policy<?> policy, final Optional<MetadataEventQueues> events,
        final CooldownService cooldown, // NOPMD UnusedFormalParameter - public API; reserved for upcoming cooldown integration in repo CRUD endpoints
        final RepositoryEventBroadcaster events2, final ArtifactIndex artifactIndex) {
        this.filtersCache = filtersCache;
        this.crs = crs;
        this.repoData = repoData;
        this.policy = policy;
        this.events = events;
        this.eventBus = events2;
        this.artifactIndex = artifactIndex == null ? ArtifactIndex.NOP : artifactIndex;
        this.fsRoots = com.auto1.pantera.settings.policy.RequestLimitsSettingsLoader.fsRootPolicy();
        this.remoteUrls = RemoteUrlPolicy.fromRegistry();
    }

    /**
     * Register repository routes on the router.
     * @param router Vert.x router
     */
    public void register(final Router router) {
        final ApiRepositoryPermission read =
            new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ);
        final ApiRepositoryPermission delete =
            new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.DELETE);
        final ApiRepositoryPermission move =
            new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.MOVE);
        // GET /api/v1/repositories — paginated list
        router.get("/api/v1/repositories")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::listRepositories);
        // SECURITY (2.2.9): the list route filters by the per-repository read
        // grant; the detail/HEAD/members routes must apply the same filter or
        // they become a visibility bypass for repositories the caller cannot
        // list.
        final RepoAuthzHandler repoRead =
            new RepoAuthzHandler(this.policy, "name", Action.Standard.READ);
        // GET /api/v1/repositories/:name — get repo config
        router.get("/api/v1/repositories/:name")
            .handler(new AuthzHandler(this.policy, read))
            .handler(repoRead)
            .handler(this::getRepository);
        // HEAD /api/v1/repositories/:name — check existence
        router.head("/api/v1/repositories/:name")
            .handler(new AuthzHandler(this.policy, read))
            .handler(repoRead)
            .handler(this::headRepository);
        // PUT /api/v1/repositories/:name — create or update
        router.put("/api/v1/repositories/:name")
            .handler(this::createOrUpdateRepository);
        // DELETE /api/v1/repositories/:name — delete
        router.delete("/api/v1/repositories/:name")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(this::deleteRepository);
        // PUT /api/v1/repositories/:name/move — rename/move
        router.put("/api/v1/repositories/:name/move")
            .handler(new AuthzHandler(this.policy, move))
            .handler(this::moveRepository);
        // GET /api/v1/repositories/:name/members — group repo members
        router.get("/api/v1/repositories/:name/members")
            .handler(new AuthzHandler(this.policy, read))
            .handler(repoRead)
            .handler(this::getMembers);
    }

    /**
     * GET /api/v1/repositories — paginated list with optional filter/search.
     * @param ctx Routing context
     */
    private void listRepositories(final RoutingContext ctx) {
        final int page = ApiResponse.intParam(ctx.queryParam("page").stream().findFirst().orElse(null), 0);
        final int size = ApiResponse.clampSize(
            ApiResponse.intParam(ctx.queryParam("size").stream().findFirst().orElse(null), 20)
        );
        final String type = ctx.queryParam("type").stream().findFirst().orElse(null);
        final String query = ctx.queryParam("q").stream().findFirst().orElse(null);
        final PermissionCollection perms = this.policy.getPermissions(
            new AuthUser(
                ctx.user().principal().getString(AuthTokenRest.SUB),
                ctx.user().principal().getString(AuthTokenRest.CONTEXT)
            )
        );
        CompletableFuture.supplyAsync((java.util.function.Supplier<List<JsonObject>>) () -> {
            final Collection<String> all = this.crs.listAll();
            final List<JsonObject> filtered = new ArrayList<>(all.size());
            for (final String name : all) {
                if (query != null
                    && !name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (!perms.implies(new AdapterBasicPermission(name, "read"))) {
                    continue;
                }
                String repoType = "unknown";
                try {
                    final javax.json.JsonStructure config =
                        this.crs.value(new RepositoryName.Simple(name));
                    if (config instanceof javax.json.JsonObject) {
                        final javax.json.JsonObject jobj = (javax.json.JsonObject) config;
                        final javax.json.JsonObject repo =
                            jobj.containsKey(RepositoryHandler.REPO)
                                ? jobj.getJsonObject(RepositoryHandler.REPO) : jobj;
                        repoType = repo.getString("type", "unknown");
                    }
                } catch (final Exception ignored) {
                    // Use "unknown" type
                }
                if (type != null && !repoType.toLowerCase(Locale.ROOT).contains(
                    type.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                filtered.add(new JsonObject()
                    .put("name", name)
                    .put("type", repoType));
            }
            return filtered;
        }, HandlerExecutor.get()).whenComplete((filtered, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                final int total = filtered.size();
                final int from = Math.min(page * size, total);
                final int to = Math.min(from + size, total);
                final JsonArray items = new JsonArray();
                for (final JsonObject item : filtered.subList(from, to)) {
                    items.add(item);
                }
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("items", items)
                        .put("page", page)
                        .put("size", size)
                        .put("total", total)
                        .put("hasMore", to < total)
                        .encode());
            }
        });
    }

    /**
     * GET /api/v1/repositories/:name — get repository config.
     * @param ctx Routing context
     */
    private void getRepository(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        final RepositoryName rname = new RepositoryName.Simple(name);
        CompletableFuture.supplyAsync((java.util.function.Supplier<JsonStructure>) () -> {
            if (!this.crs.exists(rname)) {
                return null;
            }
            // SECURITY (2.2.9, repo-config-secret): the persisted document
            // carries upstream passwords and backend credentials. The read
            // API is a redaction boundary — secrets are write-only.
            return new SecretRedactor().redact(this.crs.value(rname));
        }, HandlerExecutor.get()).whenComplete((config, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else if (config == null) {
                ApiResponse.sendError(
                    ctx, 404, "NOT_FOUND",
                    String.format("Repository '%s' not found", name)
                );
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(config.toString());
            }
        });
    }

    /**
     * HEAD /api/v1/repositories/:name — check repository existence.
     * @param ctx Routing context
     */
    private void headRepository(final RoutingContext ctx) {
        final RepositoryName rname = new RepositoryName.Simple(ctx.pathParam("name"));
        CompletableFuture.supplyAsync(
            () -> this.crs.exists(rname),
            HandlerExecutor.get()
        ).whenComplete((exists, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else if (Boolean.TRUE.equals(exists)) {
                ctx.response().setStatusCode(200).end();
            } else {
                ctx.response().setStatusCode(404).end();
            }
        });
    }

    /**
     * PUT /api/v1/repositories/:name — create or update repository.
     * @param ctx Routing context
     * @checkstyle ExecutableStatementCountCheck (90 lines)
     */
    private void createOrUpdateRepository(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        if (RepositoryHandler.refusedWhileDeleting(ctx, name)) {
            return;
        }
        if (!RepositoryHandler.validRepoName(name)) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid repository name");
            return;
        }
        final RepositoryName rname = new RepositoryName.Simple(name);
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
        if (!body.containsKey(RepositoryHandler.REPO)
            || body.getJsonObject(RepositoryHandler.REPO) == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Section `repo` is required");
            return;
        }
        final javax.json.JsonObject repo = body.getJsonObject(RepositoryHandler.REPO);
        if (!repo.containsKey("type")
            || repo.get("type").getValueType() != javax.json.JsonValue.ValueType.STRING) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Repository type is required");
            return;
        }
        final String repoType = repo.getString("type");
        if (!RepositoryHandler.TYPES.contains(repoType)) {
            ApiResponse.sendError(
                ctx, 400, "BAD_REQUEST",
                String.format(
                    "Unsupported repository type '%s'; supported types: %s",
                    repoType, String.join(", ", RepositoryHandler.TYPES.all())
                )
            );
            return;
        }
        if (RepositoryHandler.isGroupType(repoType)) {
            if (!repo.containsKey("members")
                || !(repo.get("members") instanceof javax.json.JsonArray)
                || repo.getJsonArray("members").isEmpty()) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "Group repository requires non-empty 'members' array");
                return;
            }
        } else if (!repo.containsKey("storage")) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "Repository storage is required for non-group repositories");
            return;
        }
        if (repo.containsKey("anonymous_read")) {
            final javax.json.JsonValue.ValueType vt = repo.get("anonymous_read").getValueType();
            if (vt != javax.json.JsonValue.ValueType.TRUE
                && vt != javax.json.JsonValue.ValueType.FALSE) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "anonymous_read must be a boolean");
                return;
            }
        }
        if (repo.containsKey("anonymous_write")) {
            final javax.json.JsonValue.ValueType vt = repo.get("anonymous_write").getValueType();
            if (vt != javax.json.JsonValue.ValueType.TRUE
                && vt != javax.json.JsonValue.ValueType.FALSE) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "anonymous_write must be a boolean");
                return;
            }
        }
        final Optional<String> urlError = RepositoryHandler.urlError(repo);
        if (urlError.isPresent()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", urlError.get());
            return;
        }
        // SECURITY (2.2.9): remotes[].url is an outbound destination Pantera
        // will dial on the next read. Syntax + literal/name egress check here
        // (no DNS on the event loop); the resolving check runs on the worker
        // right before the save.
        final java.util.List<String> outbound = RemoteUrlPolicy.remoteUrls(repo);
        final Optional<String> remoteError = this.remoteUrls.syntaxError(outbound);
        if (remoteError.isPresent()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", remoteError.get());
            return;
        }
        final boolean exists = this.crs.exists(rname);
        final ApiRepositoryPermission needed;
        if (exists) {
            needed = new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.UPDATE);
        } else {
            needed = new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.CREATE);
        }
        final boolean allowed = this.policy.getPermissions(
            new AuthUser(
                ctx.user().principal().getString(AuthTokenRest.SUB),
                ctx.user().principal().getString(AuthTokenRest.CONTEXT)
            )
        ).implies(needed);
        if (!allowed) {
            ApiResponse.sendError(ctx, 403, "FORBIDDEN", "Insufficient permissions");
            return;
        }
        final String actor = ctx.user().principal().getString(AuthTokenRest.SUB);
        final String clientIp = RepositoryHandler.clientIp(ctx);
        final String auditAction = exists ? "REPO_UPDATE" : "REPO_CREATE";
        CompletableFuture.runAsync(
            () -> {
                // Resolving egress check — blocks on DNS, hence on the worker.
                final Optional<String> resolved = this.remoteUrls.resolvedError(outbound);
                if (resolved.isPresent()) {
                    throw new ConfigRejected(resolved.get());
                }
                // Secrets are write-only on the read API (masked as "***"). A
                // client that round-trips the masked document must not
                // overwrite the real stored secret with the sentinel.
                final javax.json.JsonObject stored = exists
                    ? RepositoryHandler.asObject(this.crs.value(rname)) : null;
                // SECURITY (2.2.9): a raw fs path must sit under an approved
                // root — otherwise repository CREATE/UPDATE mounted the host
                // filesystem. An update that keeps the saved path is not
                // re-validated: the UI re-sends the storage block on every
                // save, and a repository created before the roots existed
                // must stay editable. Resolves symlinks, hence on the worker.
                if (!RepositoryHandler.keepsFsPath(repo, stored)) {
                    final Optional<String> badRoot = this.fsRoots.get().rejectStorage(repo);
                    if (badRoot.isPresent()) {
                        throw new ConfigRejected(badRoot.get());
                    }
                    // SECURITY (2.2.9): an inline fs/vertx-file path must not
                    // nest inside — or contain — another repository's storage,
                    // which would let this repository read or write the other's
                    // artifacts. Equal (shared-root) and sibling paths are fine.
                    final JsonValue storageVal = repo.get("storage");
                    if (storageVal != null
                        && storageVal.getValueType() == JsonValue.ValueType.OBJECT) {
                        final Optional<String> self =
                            this.fsRoots.get().localPath(storageVal.asJsonObject());
                        if (self.isPresent()) {
                            final Optional<String> clash = this.fsRoots.get().rejectOverlap(
                                name, self.get(), this.otherRepoFsPaths(name)
                            );
                            if (clash.isPresent()) {
                                throw new ConfigRejected(clash.get());
                            }
                        }
                    }
                }
                final javax.json.JsonObject merged;
                try {
                    merged = new SecretRedactor().restoreMasked(body, stored);
                } catch (final SecretRebindException ex) {
                    throw new ConfigRejected(ex.getMessage(), ex);
                }
                this.crs.save(rname, merged, actor);
            },
            HandlerExecutor.get()
        ).whenComplete((ignored, err) -> {
            if (err != null && RepositoryHandler.rootCause(err) instanceof ConfigRejected) {
                ApiResponse.sendError(
                    ctx, 400, "BAD_REQUEST", RepositoryHandler.rootCause(err).getMessage()
                );
            } else if (err != null) {
                RepositoryHandler.audit(actor, clientIp, auditAction, name,
                    java.util.Map.of(
                        "repository.type", repoType,
                        "error", String.valueOf(err.getMessage())
                    ), false);
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                this.filtersCache.invalidate(rname.toString());
                this.eventBus.publish(RepositoryEvents.upsert(name));
                RepositoryHandler.audit(actor, clientIp, auditAction, name,
                    java.util.Map.of("repository.type", repoType), true);
                ctx.response().setStatusCode(200).end();
            }
        });
    }

    /**
     * Unwrap {@link java.util.concurrent.CompletionException} layers.
     * @param err Failure
     * @return Root cause
     */
    private static Throwable rootCause(final Throwable err) {
        Throwable cause = err;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * A config refused on the worker (resolving egress check, storage root);
     * answered as {@code 400}.
     */
    private static final class ConfigRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ConfigRejected(final String message) {
            super(message);
        }

        ConfigRejected(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * A move refused on the worker (missing source, or a target that already
     * exists); answered with the carried HTTP status.
     */
    private static final class MoveRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * HTTP status to answer with.
         */
        private final int status;

        MoveRejected(final int status, final String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return this.status;
        }
    }

    /**
     * Whether a repository name is safe to persist. A repository name is used
     * as a storage path prefix, a URL path segment and (in the UI's Set Me Up
     * snippets) rendered into HTML, so it is restricted to an unambiguous,
     * path- and markup-safe character set. This rejects HTML metacharacters
     * and whitespace (closing the Set Me Up stored-XSS vector at its source),
     * path traversal ({@code ..}) and a trailing slash.
     * @param name Candidate repository name
     * @return True when the name may be created or moved to
     */
    private static boolean validRepoName(final String name) {
        return name != null && !name.isBlank() && name.length() <= 200
            && name.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")
            && !name.contains("..") && !name.endsWith("/");
    }

    /**
     * Whether an update keeps the {@code fs} storage path already saved for
     * the repository.
     * @param repo Submitted {@code repo} section
     * @param stored Stored config document, {@code null} on create
     * @return True if both are {@code fs} storage with the same path
     */
    private static boolean keepsFsPath(
        final javax.json.JsonObject repo, final javax.json.JsonObject stored
    ) {
        if (stored == null) {
            return false;
        }
        final javax.json.JsonObject saved = stored.containsKey(RepositoryHandler.REPO)
            ? stored.getJsonObject(RepositoryHandler.REPO) : stored;
        final String path = RepositoryHandler.fsPath(repo);
        return path != null && path.equals(RepositoryHandler.fsPath(saved));
    }

    /**
     * The path of an inline local-filesystem storage block ({@code fs} or
     * {@code vertx-file}), qualified by its type so switching the type of
     * a kept path is still validated.
     * @param repo A {@code repo} section
     * @return The type-qualified path, or {@code null} for any other storage
     */
    private static String fsPath(final javax.json.JsonObject repo) {
        final javax.json.JsonValue storage = repo.get("storage");
        if (storage == null || storage.getValueType() != javax.json.JsonValue.ValueType.OBJECT) {
            return null;
        }
        final javax.json.JsonObject block = storage.asJsonObject();
        return RepositoryHandler.LOCAL_PATHS.localPath(block)
            .map(path -> block.getString("type") + ":" + path)
            .orElse(null);
    }

    /**
     * The inline fs/vertx-file storage paths of every repository except the
     * named one, keyed by repository name — the input to
     * {@link FsStorageRootPolicy#rejectOverlap}. Alias- and non-fs-backed
     * repositories have no comparable inline path and are skipped, as is a
     * sibling whose config cannot be parsed (it could not itself have been
     * saved with an overlapping path).
     * @param exclude Repository being created or updated (never compared
     *  against itself)
     * @return fs storage paths by repository name
     */
    private Map<String, String> otherRepoFsPaths(final String exclude) {
        final Map<String, String> paths = new HashMap<>();
        final FsStorageRootPolicy policy = this.fsRoots.get();
        for (final String other : this.crs.listAll()) {
            if (other.equals(exclude)) {
                continue;
            }
            try {
                final javax.json.JsonObject cfg =
                    RepositoryHandler.asObject(this.crs.value(new RepositoryName.Simple(other)));
                if (cfg == null) {
                    continue;
                }
                final javax.json.JsonObject orepo = cfg.containsKey("repo")
                    && cfg.get("repo").getValueType() == JsonValue.ValueType.OBJECT
                    ? cfg.getJsonObject("repo") : cfg;
                final JsonValue storage = orepo.get("storage");
                if (storage != null && storage.getValueType() == JsonValue.ValueType.OBJECT) {
                    policy.localPath(storage.asJsonObject())
                        .ifPresent(path -> paths.put(other, path));
                }
            } catch (final RuntimeException ignored) {
                // A malformed sibling config cannot be compared; skip it.
                continue;
            }
        }
        return paths;
    }

    /**
     * Narrow a stored config structure to an object (group members etc. are
     * always objects; anything else yields {@code null} so no merge runs).
     * @param value Stored structure
     * @return The object, or {@code null}
     */
    private static javax.json.JsonObject asObject(final JsonStructure value) {
        return value instanceof javax.json.JsonObject ? (javax.json.JsonObject) value : null;
    }

    /**
     * Validate the optional client-facing {@code url:} of a repo config body.
     *
     * <p>{@code url:} is the absolute base Pantera embeds in the links this
     * repository emits, and the adapters parse it with {@code URI#toURL()} when
     * the repository is wired -- so a malformed value stored here does not fail
     * the write, it fails every later request to that repository. Rejecting it
     * at the boundary keeps a typo from taking a repository down; this became
     * reachable from the admin UI in 2.2.6, which is why it is checked here
     * rather than only in the adapters.</p>
     *
     * @param repo The {@code repo} object of the request body
     * @return Error message when the value is present and invalid, else empty
     */
    private static Optional<String> urlError(final javax.json.JsonObject repo) {
        Optional<String> result = Optional.empty();
        if (repo.containsKey("url")) {
            final String message = "url must be an absolute http(s) URL with a host";
            if (repo.get("url").getValueType() != javax.json.JsonValue.ValueType.STRING) {
                result = Optional.of(message);
            } else {
                try {
                    final java.net.URI uri = new java.net.URI(repo.getString("url"));
                    final String scheme = uri.getScheme();
                    final boolean http = "http".equalsIgnoreCase(scheme)
                        || "https".equalsIgnoreCase(scheme);
                    if (!http || uri.getHost() == null || uri.getHost().isBlank()) {
                        result = Optional.of(message);
                    }
                } catch (final java.net.URISyntaxException ex) {
                    result = Optional.of(message);
                }
            }
        }
        return result;
    }

    /**
     * DELETE /api/v1/repositories/:name — delete repository.
     *
     * <p>The data goes first, while the config still names its storage,
     * then the index rows, then the config. A failed removal keeps the
     * config so the delete can be retried -- deleting the config regardless
     * left the data behind for whoever reused the name next. The answer
     * waits at most {@link #DELETE_WAIT}: a large repository is answered
     * 202 and its outcome is logged and audited when the removal ends. A
     * delete of a name whose removal is still running answers 202 without
     * starting a second removal.</p>
     * @param ctx Routing context
     */
    private void deleteRepository(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        final RepositoryName rname = new RepositoryName.Simple(name);
        final String actor = ctx.user().principal().getString(AuthTokenRest.SUB);
        // Captured here, on the request thread: the outcome is audited from
        // the removal's completion stage on a worker, where the MDC is empty.
        final String clientIp = RepositoryHandler.clientIp(ctx);
        if (RepositoryHandler.REMOVALS.inProgress(name)) {
            RepositoryHandler.sendDeleteInProgress(ctx, name);
            return;
        }
        CompletableFuture.supplyAsync(
            () -> this.crs.exists(rname),
            HandlerExecutor.get()
        ).whenComplete((exists, err) -> {
            if (err != null) {
                RepositoryHandler.audit(actor, clientIp, "REPO_DELETE", name,
                    java.util.Map.of("error", String.valueOf(err.getMessage())),
                    false);
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                return;
            }
            if (!Boolean.TRUE.equals(exists)) {
                RepositoryHandler.audit(actor, clientIp, "REPO_DELETE", name,
                    java.util.Map.of("error", "not_found"), false);
                ApiResponse.sendError(
                    ctx, 404, "NOT_FOUND",
                    String.format("Repository '%s' not found", name)
                );
                return;
            }
            final Optional<CompletableFuture<Void>> removal = RepositoryHandler.REMOVALS.start(
                name, () -> this.removeRepository(rname, actor, clientIp)
            );
            if (removal.isEmpty()) {
                RepositoryHandler.sendDeleteInProgress(ctx, name);
                return;
            }
            RepositoryHandler.REMOVALS.answer(removal.get()).thenAccept(outcome -> {
                if (outcome.pending()) {
                    RepositoryHandler.sendDeleteInProgress(ctx, name);
                } else if (outcome.failure().isEmpty()) {
                    ctx.response().setStatusCode(200).end();
                } else {
                    ApiResponse.sendError(
                        ctx, 500, "INTERNAL_ERROR",
                        "Repository data could not be removed; the repository was kept"
                    );
                }
            });
        });
    }

    /**
     * Remove a repository: data, index rows, then config. The outcome is
     * logged and audited here, not by the HTTP answer, which may have been
     * sent before the removal ended.
     * @param rname Repository name
     * @param actor User deleting the repository
     * @param clientIp Client IP captured on the request thread, nullable
     * @return Completion of the whole removal
     */
    private CompletionStage<Void> removeRepository(
        final RepositoryName rname, final String actor, final String clientIp
    ) {
        final String name = rname.toString();
        return this.repoData.remove(rname, this.crs)
            .thenCompose(nothing -> this.artifactIndex.removeRepo(name))
            .thenAcceptAsync(rows -> this.crs.delete(rname), HandlerExecutor.get())
            .whenComplete((ignored, failure) -> {
                if (failure == null) {
                    this.filtersCache.invalidate(name);
                    this.eventBus.publish(RepositoryEvents.remove(name));
                    this.events.ifPresent(item -> item.stopProxyMetadataProcessing(name));
                    EcsLogger.info("com.auto1.pantera.api.v1")
                        .message("Repository deleted with its data")
                        .eventCategory("configuration")
                        .eventAction("repository_delete")
                        .eventOutcome("success")
                        .field("repository.name", name)
                        .field("log.source", "application")
                        .log();
                    RepositoryHandler.audit(actor, clientIp, "REPO_DELETE", name,
                        java.util.Map.of(), true);
                } else {
                    final Throwable cause = RepositoryHandler.rootCause(failure);
                    EcsLogger.error("com.auto1.pantera.api.v1")
                        .message("Repository delete failed, the repository was kept")
                        .eventCategory("configuration")
                        .eventAction("repository_delete")
                        .eventOutcome("failure")
                        .field("repository.name", name)
                        .error(cause)
                        .field("log.source", "application")
                        .log();
                    RepositoryHandler.audit(actor, clientIp, "REPO_DELETE", name,
                        java.util.Map.of("error", String.valueOf(cause.getMessage())),
                        false);
                }
            });
    }

    /**
     * Answer 202: the repository's removal is still running.
     * @param ctx Routing context
     * @param name Repository name
     */
    private static void sendDeleteInProgress(final RoutingContext ctx, final String name) {
        ctx.response()
            .setStatusCode(202)
            .putHeader("Content-Type", "application/json")
            .end(
                new JsonObject()
                    .put("status", "deleting")
                    .put(
                        "message",
                        String.format(
                            "Repository '%s' is being deleted; it disappears from the list when its data is removed",
                            name
                        )
                    ).encode()
            );
    }

    /**
     * Refuse a change to a repository whose delete is still running: the
     * running delete would remove the config written now.
     * @param ctx Routing context
     * @param name Repository name
     * @return True when refused (answer sent)
     */
    private static boolean refusedWhileDeleting(final RoutingContext ctx, final String name) {
        final boolean deleting = RepositoryHandler.REMOVALS.inProgress(name);
        if (deleting) {
            ApiResponse.sendError(
                ctx, 409, "CONFLICT",
                String.format("Repository '%s' is being deleted; retry when the delete has finished", name)
            );
        }
        return deleting;
    }

    /**
     * PUT /api/v1/repositories/:name/move — rename/move repository.
     * @param ctx Routing context
     */
    private void moveRepository(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        if (RepositoryHandler.refusedWhileDeleting(ctx, name)) {
            return;
        }
        final RepositoryName rname = new RepositoryName.Simple(name);
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
        final String newName = body.getString("new_name", "").trim();
        if (newName.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "new_name is required");
            return;
        }
        if (!RepositoryHandler.validRepoName(newName)) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid repository name");
            return;
        }
        if (RepositoryHandler.refusedWhileDeleting(ctx, newName)) {
            return;
        }
        final RepositoryName newrname = new RepositoryName.Simple(newName);
        final String actor = ctx.user().principal().getString(AuthTokenRest.SUB);
        final String clientIp = RepositoryHandler.clientIp(ctx);
        // SECURITY (2.2.9): reject a move onto an existing repository. The data
        // move copies the source subtree over the target's storage and then
        // deletes the source, so renaming onto a live repository would destroy
        // its artifacts. Await the whole move before answering, and audit it.
        CompletableFuture.supplyAsync(
            () -> {
                if (!this.crs.exists(rname)) {
                    throw new MoveRejected(404, String.format("Repository '%s' not found", name));
                }
                if (this.crs.exists(newrname)) {
                    throw new MoveRejected(
                        409, String.format("Repository '%s' already exists", newName)
                    );
                }
                return null;
            },
            HandlerExecutor.get()
        ).thenCompose(
            ignored -> this.repoData.move(rname, newrname, this.crs)
        ).thenRunAsync(
            () -> this.crs.move(rname, newrname), HandlerExecutor.get()
        ).whenComplete((ignored, err) -> {
            if (err != null) {
                final Throwable cause = RepositoryHandler.rootCause(err);
                if (cause instanceof MoveRejected rejected) {
                    ApiResponse.sendError(
                        ctx, rejected.status(),
                        rejected.status() == 409 ? "CONFLICT" : "NOT_FOUND",
                        cause.getMessage()
                    );
                } else {
                    RepositoryHandler.audit(actor, clientIp, "REPO_MOVE", name,
                        java.util.Map.of("new_name", newName,
                            "error", String.valueOf(err.getMessage())), false);
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
                return;
            }
            this.filtersCache.invalidate(rname.toString());
            this.eventBus.publish(RepositoryEvents.move(name, newName));
            RepositoryHandler.audit(actor, clientIp, "REPO_MOVE", name,
                java.util.Map.of("new_name", newName), true);
            ctx.response().setStatusCode(200).end();
        });
    }

    /**
     * Returns true when the given repository type is a group type
     * (i.e. its name ends with the {@code -group} suffix).
     * Group repositories are pure routing abstractions — they have no
     * storage of their own, only a {@code members} list.
     * @param type Repository type string (may be null)
     * @return True if the type ends with {@code -group}
     */
    private static boolean isGroupType(final String type) {
        return type != null && type.endsWith("-group");
    }

    /**
     * GET /api/v1/repositories/:name/members — get group repository members.
     * @param ctx Routing context
     */
    private void getMembers(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        final RepositoryName rname = new RepositoryName.Simple(name);
        CompletableFuture.supplyAsync((java.util.function.Supplier<JsonObject>) () -> {
            if (!this.crs.exists(rname)) {
                return null;
            }
            final JsonStructure config = this.crs.value(rname);
            if (config == null) {
                return null;
            }
            final javax.json.JsonObject jconfig;
            if (config instanceof javax.json.JsonObject) {
                jconfig = (javax.json.JsonObject) config;
            } else {
                return new JsonObject().put("members", new JsonArray()).put("type", "not-a-group");
            }
            final javax.json.JsonObject repoSection = jconfig.containsKey(RepositoryHandler.REPO)
                ? jconfig.getJsonObject(RepositoryHandler.REPO) : jconfig;
            final String repoType = repoSection.getString("type", "");
            if (!repoType.endsWith("-group")) {
                return new JsonObject().put("members", new JsonArray()).put("type", "not-a-group");
            }
            // A group lists its member repositories under "members" (the
            // key the PUT validation requires); "remotes" is a proxy's
            // upstream list and never holds a group's members.
            final JsonArray members = new JsonArray();
            final javax.json.JsonValue listed = repoSection.get("members");
            if (listed != null && listed.getValueType() == javax.json.JsonValue.ValueType.ARRAY) {
                for (final javax.json.JsonValue member : listed.asJsonArray()) {
                    if (member.getValueType() == javax.json.JsonValue.ValueType.STRING) {
                        members.add(((javax.json.JsonString) member).getString());
                    }
                }
            }
            return new JsonObject().put("members", members).put("type", repoType);
        }, HandlerExecutor.get()).whenComplete((result, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else if (result == null) {
                ApiResponse.sendError(
                    ctx, 404, "NOT_FOUND",
                    String.format("Repository '%s' not found", name)
                );
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            }
        });
    }

    /**
     * T-S04: emit an audit event for an admin mutation. Looks up the shared
     * {@link com.auto1.pantera.audit.AuditService} via the registry — when no
     * service is installed (tests / DB-less boot) this is a no-op. Reads
     * the client IP as captured on the request thread by the caller: the
     * record may be written from a worker-thread completion stage, where
     * the MDC slot set by the trace-context handler is empty.
     *
     * @param actor Authenticated principal
     * @param clientIp Client IP captured before any async hop, nullable
     * @param action Action verb (SCREAMING_SNAKE_CASE)
     * @param target Repository name
     * @param details Structured payload for the {@code details} JSON column
     * @param success {@code true} on completed mutation
     */
    private static void audit(final String actor, final String clientIp,
        final String action, final String target,
        final java.util.Map<String, Object> details, final boolean success) {
        final com.auto1.pantera.audit.AuditEvent event =
            new com.auto1.pantera.audit.AuditEvent(
                java.time.Instant.now(), actor, action, target,
                details, success, clientIp
            );
        com.auto1.pantera.audit.AuditServiceRegistry.instance()
            .sharedService().record(event);
    }

    /**
     * The client IP of an API request, as bound by the trace-context handler
     * on the routing context (it survives async hops, unlike the MDC).
     * @param ctx Routing context
     * @return Client IP, or null when unknown
     */
    private static String clientIp(final RoutingContext ctx) {
        final String bound = new ApiAuditContext(ctx).value().clientIp();
        final String res;
        if (bound == null) {
            res = org.slf4j.MDC.get(com.auto1.pantera.http.log.EcsMdc.CLIENT_IP);
        } else {
            res = bound;
        }
        return res;
    }
}
