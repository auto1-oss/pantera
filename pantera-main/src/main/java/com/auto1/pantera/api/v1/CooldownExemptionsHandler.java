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
import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.api.perms.ApiCooldownPermission;
import com.auto1.pantera.cache.CacheInvalidationPubSub;
import com.auto1.pantera.cooldown.ArchiveReason;
import com.auto1.pantera.cooldown.CooldownExemptionStore;
import com.auto1.pantera.cooldown.CooldownRepository;
import com.auto1.pantera.cooldown.cache.CooldownCache;
import com.auto1.pantera.cooldown.config.CooldownExemptions;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Per-repository cooldown exemptions:
 * {@code GET/PUT /api/v1/repositories/:name/cooldown/exemptions}.
 *
 * <p>PUT replaces the repository's whole list. Saving it writes the table,
 * swaps the in-memory matcher, releases the active blocks of newly exempt
 * packages, drops the repository's cooldown decision and filtered-metadata
 * caches and tells the other nodes to reload.</p>
 *
 * @since 2.2.11
 */
public final class CooldownExemptionsHandler {

    /**
     * Most rules one repository may hold.
     */
    private static final int MAX_RULES = 500;

    /**
     * JSON key of the rule list.
     */
    private static final String EXEMPTIONS = "exemptions";

    /**
     * JSON key of a rule's pattern.
     */
    private static final String PATTERN = "pattern";

    /**
     * Exemption store.
     */
    private final CooldownExemptionStore store;

    /**
     * Block rows.
     */
    private final CooldownRepository repository;

    /**
     * Filtered-metadata service.
     */
    private final CooldownMetadataService metadata;

    /**
     * Cooldown decision cache, may be null.
     */
    private final CooldownCache cache;

    /**
     * Repository settings.
     */
    private final CrudRepoSettings crs;

    /**
     * Security policy.
     */
    private final Policy<?> policy;

    /**
     * Cluster cache-invalidation bus.
     */
    private final Optional<CacheInvalidationPubSub> bus;

    /**
     * Ctor.
     *
     * @param store Exemption store
     * @param repository Block rows
     * @param metadata Filtered-metadata service
     * @param cache Cooldown decision cache, may be null
     * @param crs Repository settings
     * @param policy Security policy
     * @param bus Cluster cache-invalidation bus
     * @checkstyle ParameterNumberCheck (10 lines)
     */
    public CooldownExemptionsHandler(
        final CooldownExemptionStore store,
        final CooldownRepository repository,
        final CooldownMetadataService metadata,
        final CooldownCache cache,
        final CrudRepoSettings crs,
        final Policy<?> policy,
        final Optional<CacheInvalidationPubSub> bus
    ) {
        this.store = store;
        this.repository = repository;
        this.metadata = metadata;
        this.cache = cache;
        this.crs = crs;
        this.policy = policy;
        this.bus = bus;
    }

    /**
     * Register the routes.
     *
     * @param router Router
     */
    public void register(final Router router) {
        router.get("/api/v1/repositories/:name/cooldown/exemptions")
            .handler(new AuthzHandler(this.policy, ApiCooldownPermission.READ))
            .handler(new RepoAuthzHandler(this.policy, "name", Action.Standard.READ))
            .handler(this::get);
        router.put("/api/v1/repositories/:name/cooldown/exemptions")
            .handler(new AuthzHandler(this.policy, ApiCooldownPermission.WRITE))
            .handler(new RepoAuthzHandler(this.policy, "name", Action.Standard.WRITE))
            .handler(this::put);
    }

    private void get(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        CompletableFuture.supplyAsync(
            () -> {
                this.repoType(name);
                return CooldownExemptionsHandler.body(name, this.store.current(name), 0);
            },
            HandlerExecutor.get()
        ).whenComplete((body, err) -> this.respond(ctx, body, err));
    }

    private void put(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        final String actor = ctx.user().principal().getString(AuthTokenRest.SUB, "system");
        final JsonObject request;
        try {
            request = ctx.body().asJsonObject();
        } catch (final io.vertx.core.json.DecodeException ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Body must be a JSON object");
            return;
        }
        final JsonArray list = request == null ? null : request.getJsonArray(EXEMPTIONS);
        if (list == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Body must carry an \"exemptions\" array");
            return;
        }
        CompletableFuture.supplyAsync(
            () -> {
                final String type = this.repoType(name);
                final CooldownExemptions before = this.store.current(name);
                final List<CooldownExemptions.Rule> rules =
                    CooldownExemptionsHandler.rules(list, before, actor, type);
                final CooldownExemptions after = this.store.replace(name, rules);
                final int released = this.release(name, after, actor);
                if (this.cache != null) {
                    this.cache.unblockAll(name);
                }
                this.metadata.invalidateAll(type, name);
                this.bus.ifPresent(pubsub -> pubsub.publish(CooldownExemptionStore.CHANNEL, name));
                EcsLogger.info("com.auto1.pantera.cooldown")
                    .message(
                        "Cooldown exemptions replaced: " + before.rules().size() + " -> "
                            + after.rules().size() + " rules, " + released + " active blocks released"
                    )
                    .eventCategory("configuration")
                    .eventAction("cooldown_exemptions_update")
                    .eventOutcome("success")
                    .field("repository.name", name)
                    .field("repository.type", type)
                    .field("user.name", actor)
                    .field("log.source", "application")
                    .log();
                com.auto1.pantera.audit.AuditServiceRegistry.instance().sharedService().record(
                    new com.auto1.pantera.audit.AuditEvent(
                        Instant.now(), actor, "COOLDOWN_EXEMPTIONS_UPDATE", name,
                        Map.<String, Object>of("repository.type", type, "released", released), true,
                        CooldownExemptionsHandler.clientIp(ctx),
                        CooldownExemptionsHandler.body(name, before, 0).encode(),
                        CooldownExemptionsHandler.body(name, after, 0).encode()
                    )
                );
                return CooldownExemptionsHandler.body(name, after, released);
            },
            HandlerExecutor.get()
        ).whenComplete((body, err) -> this.respond(ctx, body, err));
    }

    /**
     * Release the active blocks the unexpired rules now cover.
     */
    private int release(final String name, final CooldownExemptions exemptions, final String actor) {
        final long now = System.currentTimeMillis();
        final List<String> exact = new ArrayList<>();
        final List<String> prefixes = new ArrayList<>();
        for (final CooldownExemptions.Rule rule : exemptions.rules()) {
            if (rule.expiresAt().map(at -> at.toEpochMilli() > now).orElse(true)) {
                final String pattern = rule.pattern().toLowerCase(Locale.ROOT);
                if (pattern.endsWith("*")) {
                    prefixes.add(pattern.substring(0, pattern.length() - 1));
                } else {
                    exact.add(pattern);
                }
            }
        }
        return this.repository == null ? 0 : this.repository.archiveAndReleaseMatching(
            name, exact, prefixes, ArchiveReason.MANUAL_UNBLOCK, actor
        );
    }

    /**
     * Type of an existing proxy repository.
     *
     * @throws ApiException 404 when it does not exist, 400 when it is not a proxy
     */
    private String repoType(final String name) {
        final RepositoryName rname = new RepositoryName.Simple(name);
        if (!this.crs.exists(rname)) {
            throw new ApiException(404, "NOT_FOUND", "Repository '" + name + "' not found");
        }
        final javax.json.JsonStructure config = this.crs.value(rname);
        String type = "";
        if (config instanceof javax.json.JsonObject obj
            && obj.get("repo") instanceof javax.json.JsonObject repo) {
            type = repo.getString("type", "");
        }
        if (!type.endsWith("-proxy")) {
            throw new ApiException(
                400, "BAD_REQUEST",
                "Cooldown exemptions apply to proxy repositories; '" + name + "' is " + type
            );
        }
        return type;
    }

    private void respond(final RoutingContext ctx, final JsonObject body, final Throwable err) {
        Throwable cause = err;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause == null) {
            ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(body.encode());
        } else if (cause instanceof ApiException api) {
            ApiResponse.sendError(ctx, api.status, api.code, api.getMessage());
        } else {
            EcsLogger.error("com.auto1.pantera.cooldown")
                .message("Cooldown exemptions request failed")
                .eventCategory("configuration")
                .eventAction("cooldown_exemptions_update")
                .eventOutcome("failure")
                .field("repository.name", ctx.pathParam("name"))
                .error(cause)
                .field("log.source", "application")
                .log();
            ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", "Failed to process cooldown exemptions");
        }
    }

    /**
     * Validate the requested list into rules. A pattern that already exists
     * keeps who added it and when.
     *
     * @throws ApiException 400 on an invalid list
     */
    private static List<CooldownExemptions.Rule> rules(
        final JsonArray list, final CooldownExemptions current, final String actor,
        final String type
    ) {
        if (list.size() > MAX_RULES) {
            throw new ApiException(400, "BAD_REQUEST", "At most " + MAX_RULES + " exemptions per repository");
        }
        final Map<String, CooldownExemptions.Rule> existing = new HashMap<>();
        current.rules().forEach(rule -> existing.put(rule.pattern(), rule));
        final Set<String> seen = new HashSet<>();
        final List<CooldownExemptions.Rule> rules = new ArrayList<>(list.size());
        // Millisecond precision survives the TIMESTAMPTZ round trip unchanged,
        // so the periodic refresh sees the stored rules as equal to these.
        final Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        for (int idx = 0; idx < list.size(); ++idx) {
            final Object item = list.getValue(idx);
            if (!(item instanceof JsonObject entry) || !(entry.getValue(PATTERN) instanceof String)) {
                throw new ApiException(400, "BAD_REQUEST", "Each exemption needs a string \"pattern\"");
            }
            final String pattern = CooldownExemptionsHandler.canonical(
                type, entry.getString(PATTERN).trim()
            );
            if (!seen.add(pattern.toLowerCase(Locale.ROOT))) {
                throw new ApiException(400, "BAD_REQUEST", "Duplicate exemption pattern: " + pattern);
            }
            final Optional<Instant> expires = CooldownExemptionsHandler.expiry(entry, now);
            final CooldownExemptions.Rule prior = existing.get(pattern);
            try {
                rules.add(
                    new CooldownExemptions.Rule(
                        pattern, expires, java.util.Objects.requireNonNullElse(entry.getString("reason"), ""),
                        prior == null ? actor : prior.createdBy(),
                        prior == null ? now : prior.createdAt()
                    )
                );
            } catch (final IllegalArgumentException ex) {
                throw new ApiException(400, "BAD_REQUEST", ex.getMessage(), ex);
            }
        }
        return rules;
    }

    /**
     * Write a pattern the way the adapter names packages, so it matches the
     * cooldown blocked list: Maven/Gradle {@code groupId:artifactId} becomes
     * {@code groupId.artifactId}, a PyPI name is PEP 503-normalised.
     */
    private static String canonical(final String type, final String pattern) {
        final String result;
        if ("maven-proxy".equals(type) || "gradle-proxy".equals(type)) {
            result = pattern.replace(':', '.');
        } else if ("pypi-proxy".equals(type)) {
            final boolean prefix = pattern.endsWith("*");
            final String name = prefix ? pattern.substring(0, pattern.length() - 1) : pattern;
            result = name.replaceAll("[-_.]+", "-").toLowerCase(Locale.ROOT) + (prefix ? "*" : "");
        } else {
            result = pattern;
        }
        return result;
    }

    private static Optional<Instant> expiry(final JsonObject entry, final Instant now) {
        final Object raw = entry.getValue("expires_at");
        final Optional<Instant> result;
        if (raw == null) {
            result = Optional.empty();
        } else if (raw instanceof String text) {
            final Instant at;
            try {
                at = Instant.parse(text);
            } catch (final DateTimeParseException ex) {
                throw new ApiException(
                    400, "BAD_REQUEST",
                    "expires_at must be an ISO-8601 instant such as 2026-12-31T00:00:00Z: " + text,
                    ex
                );
            }
            if (!at.isAfter(now)) {
                throw new ApiException(400, "BAD_REQUEST", "expires_at must be in the future: " + text);
            }
            result = Optional.of(at.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        } else {
            throw new ApiException(400, "BAD_REQUEST", "expires_at must be a string or null");
        }
        return result;
    }

    private static JsonObject body(final String name, final CooldownExemptions exemptions, final int released) {
        final long now = System.currentTimeMillis();
        final JsonArray list = new JsonArray();
        for (final CooldownExemptions.Rule rule : exemptions.rules()) {
            list.add(
                new JsonObject()
                    .put(PATTERN, rule.pattern())
                    .put("expires_at", rule.expiresAt().map(Instant::toString).orElse(null))
                    .put("reason", rule.reason())
                    .put("created_by", rule.createdBy())
                    .put("created_at", rule.createdAt().toString())
                    .put("active", rule.expiresAt().map(at -> at.toEpochMilli() > now).orElse(true))
            );
        }
        return new JsonObject()
            .put("repository", name)
            .put(EXEMPTIONS, list)
            .put("released", released);
    }

    private static String clientIp(final RoutingContext ctx) {
        final String forwarded = ctx.request().getHeader("X-Forwarded-For");
        final String result;
        if (forwarded != null && !forwarded.isBlank()) {
            result = forwarded.split(",", 2)[0].trim();
        } else if (ctx.request().remoteAddress() != null) {
            result = ctx.request().remoteAddress().host();
        } else {
            result = null;
        }
        return result;
    }

    /**
     * A request failure with its HTTP status.
     */
    private static final class ApiException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * HTTP status.
         */
        private final int status;

        /**
         * Error code.
         */
        private final String code;

        ApiException(final int status, final String code, final String message) {
            this(status, code, message, null);
        }

        ApiException(final int status, final String code, final String message, final Throwable cause) {
            super(message, cause);
            this.status = status;
            this.code = code;
        }
    }
}
