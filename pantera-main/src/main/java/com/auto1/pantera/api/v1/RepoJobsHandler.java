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
import com.auto1.pantera.api.perms.ApiRepositoryPermission;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.repojob.RepoJob;
import com.auto1.pantera.repojob.RepoLifecycleDao;
import com.auto1.pantera.repojob.RepoOpRejected;
import com.auto1.pantera.repojob.RepoOps;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Repository jobs and deleted repositories.
 *
 * <ul>
 *   <li>{@code GET /api/v1/repository-jobs} - purge and rename follow-up
 *   jobs with their progress.</li>
 *   <li>{@code GET /api/v1/repository-jobs/:id} - one job.</li>
 *   <li>{@code POST /api/v1/repository-jobs/:id/retry} - retry a job that
 *   gave up.</li>
 *   <li>{@code POST /api/v1/repository-jobs/:id/start} - end the restore
 *   window of a delete and purge now.</li>
 *   <li>{@code GET /api/v1/deleted-repositories} - repositories deleted and
 *   not purged yet.</li>
 *   <li>{@code POST /api/v1/deleted-repositories/:key/restore} - undo a
 *   delete whose purge has not started.</li>
 *   <li>{@code GET /api/v1/repository-orphans} - storage directories no
 *   repository owns.</li>
 *   <li>{@code POST /api/v1/repository-orphans/purge} - queue the purge of
 *   one of them.</li>
 * </ul>
 *
 * @since 2.2.14
 */
public final class RepoJobsHandler {

    /**
     * Most jobs returned by the list.
     */
    private static final int LIST_LIMIT = 200;

    /**
     * Repository operations.
     */
    private final RepoOps ops;

    /**
     * Security policy.
     */
    private final Policy<?> policy;

    /**
     * Repository lifecycle events (local bus and peers).
     */
    private final RepositoryEventBroadcaster events;

    /**
     * Ctor.
     * @param ops Repository operations
     * @param policy Security policy
     * @param events Repository lifecycle events
     */
    public RepoJobsHandler(
        final RepoOps ops, final Policy<?> policy, final RepositoryEventBroadcaster events
    ) {
        this.ops = ops;
        this.policy = policy;
        this.events = events;
    }

    /**
     * Register the routes.
     * @param router Router
     */
    public void register(final Router router) {
        final ApiRepositoryPermission delete =
            new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.DELETE);
        router.get("/api/v1/repository-jobs")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(this::list);
        router.get("/api/v1/repository-jobs/:id")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(this::get);
        router.post("/api/v1/repository-jobs/:id/retry")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(ctx -> this.change(ctx, "REPO_JOB_RETRY", "failed"));
        router.post("/api/v1/repository-jobs/:id/start")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(ctx -> this.change(ctx, "REPO_JOB_START", "waiting"));
        router.get("/api/v1/deleted-repositories")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(this::deleted);
        router.get("/api/v1/repository-orphans")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(this::orphans);
        router.post("/api/v1/repository-orphans/purge")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(this::purgeOrphan);
        router.post("/api/v1/deleted-repositories/:key/restore")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(this::restore);
    }

    /**
     * GET /api/v1/repository-jobs.
     * @param ctx Routing context
     */
    private void list(final RoutingContext ctx) {
        RepoJobsHandler.async(
            ctx,
            () -> {
                final JsonArray items = new JsonArray();
                this.ops.jobs().list(RepoJobsHandler.LIST_LIMIT)
                    .forEach(job -> items.add(RepoJobsHandler.json(job)));
                return new JsonObject().put("items", items);
            }
        );
    }

    /**
     * GET /api/v1/repository-jobs/:id.
     * @param ctx Routing context
     */
    private void get(final RoutingContext ctx) {
        final long id;
        try {
            id = Long.parseLong(ctx.pathParam("id"));
        } catch (final NumberFormatException ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Job id must be a number");
            return;
        }
        RepoJobsHandler.async(
            ctx,
            () -> this.ops.jobs().get(id).map(RepoJobsHandler::json).orElseThrow(
                () -> new RepoOpRejected(404, "NOT_FOUND", String.format("No job %d", id))
            )
        );
    }

    /**
     * POST /api/v1/repository-jobs/:id/retry and /start.
     * @param ctx Routing context
     * @param action Audit action
     * @param expected What the job must be for the change to apply
     */
    private void change(final RoutingContext ctx, final String action, final String expected) {
        final long id;
        try {
            id = Long.parseLong(ctx.pathParam("id"));
        } catch (final NumberFormatException ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Job id must be a number");
            return;
        }
        final String actor = ctx.user().principal().getString(AuthTokenRest.SUB);
        final String clientIp = new ApiAuditContext(ctx).value().clientIp();
        RepoJobsHandler.async(
            ctx,
            () -> {
                final boolean changed = "failed".equals(expected)
                    ? this.ops.jobs().retry(id) : this.ops.jobs().startNow(id);
                RepoJobsHandler.audit(
                    actor, clientIp, action, String.valueOf(id), Map.of(), changed
                );
                if (!changed) {
                    throw new RepoOpRejected(
                        409, "CONFLICT", String.format("Job %d is not %s", id, expected)
                    );
                }
                this.ops.wake();
                return this.ops.jobs().get(id).map(RepoJobsHandler::json)
                    .orElseGet(JsonObject::new);
            }
        );
    }

    /**
     * GET /api/v1/deleted-repositories.
     * @param ctx Routing context
     */
    private void deleted(final RoutingContext ctx) {
        RepoJobsHandler.async(
            ctx,
            () -> {
                final JsonArray items = new JsonArray();
                for (final RepoLifecycleDao.Tombstone tomb : this.ops.lifecycle().tombstones()) {
                    items.add(
                        new JsonObject()
                            .put("repo_key", tomb.key())
                            .put("name", tomb.name())
                            .put("type", tomb.type())
                            .put("deleted_at", tomb.deletedAt().toString())
                            .put("deleted_by", tomb.deletedBy())
                            .put("purge_after", tomb.purgeAfter().toString())
                    );
                }
                return new JsonObject().put("items", items);
            }
        );
    }

    /**
     * POST /api/v1/deleted-repositories/:key/restore.
     * @param ctx Routing context
     */
    private void restore(final RoutingContext ctx) {
        final String key = ctx.pathParam("key");
        final JsonObject body = RepoJobsHandler.empty(ctx)
            ? new JsonObject() : RepoJobsHandler.parse(ctx);
        if (body == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid JSON body");
            return;
        }
        final String target = body.getString("name");
        final String actor = ctx.user().principal().getString(AuthTokenRest.SUB);
        final String clientIp = new ApiAuditContext(ctx).value().clientIp();
        RepoJobsHandler.async(
            ctx,
            () -> {
                final RepoLifecycleDao.Restored restored;
                try {
                    restored = this.ops.lifecycle().restore(key, target, actor);
                } catch (final RepoOpRejected rejected) {
                    RepoJobsHandler.audit(
                        actor, clientIp, "REPO_RESTORE", key,
                        Map.of("error", rejected.getMessage()), false
                    );
                    throw rejected;
                }
                this.ops.rolesChanged(restored.roles());
                this.events.publish(RepositoryEvents.upsert(restored.name()));
                restored.groups().forEach(
                    group -> this.events.publish(RepositoryEvents.upsert(group))
                );
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("Deleted repository restored before its purge started")
                    .eventCategory("configuration")
                    .eventAction("repository_restore")
                    .eventOutcome("success")
                    .field("repository.name", restored.name())
                    .field("log.source", "application")
                    .log();
                RepoJobsHandler.audit(
                    actor, clientIp, "REPO_RESTORE", restored.name(),
                    Map.of("repo_key", key), true
                );
                return new JsonObject()
                    .put("name", restored.name())
                    .put("repo_key", key)
                    .put("rejoined_groups", new JsonArray(restored.groups()));
            }
        );
    }

    /**
     * GET /api/v1/repository-orphans.
     * @param ctx Routing context
     */
    private void orphans(final RoutingContext ctx) {
        RepoJobsHandler.async(
            ctx,
            () -> {
                final JsonArray items = new JsonArray();
                this.ops.orphans().scan().forEach(
                    orphan -> items.add(
                        new JsonObject()
                            .put("storage", orphan.storage())
                            .put("location", orphan.location())
                            .put("directory", orphan.directory())
                            .put("objects", orphan.objects())
                            .put("more", orphan.more())
                    )
                );
                return new JsonObject().put("items", items);
            }
        );
    }

    /**
     * POST /api/v1/repository-orphans/purge.
     * @param ctx Routing context
     */
    private void purgeOrphan(final RoutingContext ctx) {
        final JsonObject body = RepoJobsHandler.empty(ctx) ? null : RepoJobsHandler.parse(ctx);
        final String storage = body == null ? null : body.getString("storage");
        final String dir = body == null ? null : body.getString("directory");
        if (storage == null || storage.isBlank() || dir == null || dir.isBlank()) {
            ApiResponse.sendError(
                ctx, 400, "BAD_REQUEST", "JSON body with storage and directory is required"
            );
            return;
        }
        final String actor = ctx.user().principal().getString(AuthTokenRest.SUB);
        final String clientIp = new ApiAuditContext(ctx).value().clientIp();
        final String trace = new ApiAuditContext(ctx).value().traceId();
        RepoJobsHandler.async(
            ctx, 202,
            () -> {
                final javax.json.JsonObject config = this.ops.orphans().confirm(storage, dir)
                    .orElseThrow(
                        () -> {
                            RepoJobsHandler.audit(
                                actor, clientIp, "REPO_ORPHAN_PURGE", dir,
                                Map.of("storage", storage, "error", "not an orphan"), false
                            );
                            return new RepoOpRejected(
                                409, "CONFLICT",
                                String.format(
                                    "Directory '%s' is not an unowned directory of storage %s",
                                    dir, storage
                                )
                            );
                        }
                    );
                final long job = this.ops.jobs().enqueue(
                    RepoJob.Type.ORPHAN_PURGE, dir, dir,
                    javax.json.Json.createObjectBuilder().add("storage", config).build(),
                    actor, trace
                );
                this.ops.wake();
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message(
                        String.format(
                            "Purge of unowned storage directory '%s' queued as job %d", dir, job
                        )
                    )
                    .eventCategory("configuration")
                    .eventAction("repository_orphan_purge")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
                RepoJobsHandler.audit(
                    actor, clientIp, "REPO_ORPHAN_PURGE", dir,
                    Map.of("storage", storage, "job_id", job), true
                );
                return new JsonObject().put("status", "purging").put("job_id", job);
            }
        );
    }

    /**
     * Run blocking work on the API worker pool and answer its JSON.
     * @param ctx Routing context
     * @param work Work
     */
    private static void async(final RoutingContext ctx, final Supplier<JsonObject> work) {
        RepoJobsHandler.async(ctx, 200, work);
    }

    /**
     * Run blocking work on the API worker pool and answer its JSON.
     * @param ctx Routing context
     * @param status Status of a successful answer
     * @param work Work
     */
    private static void async(
        final RoutingContext ctx, final int status, final Supplier<JsonObject> work
    ) {
        CompletableFuture.supplyAsync(work, HandlerExecutor.get()).whenComplete(
            (json, err) -> {
                if (err == null) {
                    ctx.response()
                        .setStatusCode(status)
                        .putHeader("Content-Type", "application/json")
                        .end(json.encode());
                } else {
                    Throwable cause = err;
                    while (cause instanceof CompletionException && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    if (cause instanceof RepoOpRejected rejected) {
                        ApiResponse.sendError(
                            ctx, rejected.status(), rejected.error(), rejected.getMessage()
                        );
                    } else {
                        ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err);
                    }
                }
            }
        );
    }

    /**
     * Whether the request has no body.
     * @param ctx Routing context
     * @return True when there is nothing to parse
     */
    private static boolean empty(final RoutingContext ctx) {
        final String text = ctx.body() == null ? null : ctx.body().asString();
        return text == null || text.isBlank();
    }

    /**
     * Parse the request body.
     * @param ctx Routing context
     * @return Body, {@code null} when it is not a JSON object
     */
    private static JsonObject parse(final RoutingContext ctx) {
        JsonObject res;
        try {
            res = ctx.body().asJsonObject();
        } catch (final RuntimeException ex) {
            res = null;
        }
        return res;
    }

    /**
     * JSON of a job.
     * @param job Job
     * @return JSON
     */
    private static JsonObject json(final RepoJob job) {
        return new JsonObject()
            .put("id", job.id())
            .put("type", job.type().db())
            .put("repo_key", job.key())
            .put("repo_name", job.name())
            .put("state", job.state())
            .put("phase", job.phase())
            .put("objects_done", job.objects())
            .put("rows_done", job.rows())
            .put("attempts", job.attempts())
            .put("last_error", job.error())
            .put("not_before", job.notBefore().toString())
            .put("node", job.owner())
            .put("created_by", job.createdBy())
            .put("created_at", job.created().toString())
            .put("updated_at", job.updated().toString())
            .put("finished_at", job.finished() == null ? null : job.finished().toString());
    }

    /**
     * Record an audit event.
     * @param actor Who
     * @param clientIp Client IP, nullable
     * @param action Action
     * @param target What it was done to
     * @param details Details
     * @param success Whether it succeeded
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private static void audit(
        final String actor, final String clientIp, final String action, final String target,
        final Map<String, Object> details, final boolean success
    ) {
        com.auto1.pantera.audit.AuditServiceRegistry.instance().sharedService().record(
            new com.auto1.pantera.audit.AuditEvent(
                java.time.Instant.now(), actor, action, target, details, success, clientIp
            )
        );
    }
}
