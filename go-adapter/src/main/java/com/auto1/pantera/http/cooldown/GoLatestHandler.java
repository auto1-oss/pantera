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
package com.auto1.pantera.http.cooldown;

import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.auto1.pantera.cooldown.metadata.VersionComparators;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates cooldown-aware rewriting of Go {@code /@latest} responses.
 *
 * <p>Closes the "unbounded-resolution gap" where {@code go get <module>}
 * (without a pseudo-version) hits {@code /@latest} and never consults
 * {@code /@v/list}. The flow is:</p>
 *
 * <ol>
 *   <li>Resolve the raw {@code /@latest} base document via
 *       {@link GoMetadataBaseLoader} (WS4-go.2): TTL-cached, offline-safe
 *       on a warm module, single-flighted on a cold miss, cooldown
 *       re-evaluated per request over the cached base.</li>
 *   <li>Parse the JSON into {@link GoLatestInfo}. If malformed, pass the
 *       base bytes through unchanged — we never break clients on
 *       upstream weirdness.</li>
 *   <li>Check the {@code Version} against cooldown. If not blocked,
 *       return the base document unchanged.</li>
 *   <li>If blocked, resolve the sibling {@code /@v/list} base (through
 *       the same loader, sharing its cache entry and single-flight gate
 *       with {@link GoListHandler}), evaluate every version against
 *       cooldown, and pick the highest non-blocked one under the same
 *       semver-ish ordering the Go toolchain uses
 *       ({@link VersionComparators#semver()}, which handles {@code v}
 *       prefix and tolerates pseudo-versions).</li>
 *   <li>Rewrite the {@code @latest} JSON with the fallback version,
 *       preserving {@code Origin}; clear {@code Time} because it no
 *       longer matches the served version and the Go client treats the
 *       field as optional.</li>
 *   <li>If <em>every</em> version is blocked, return HTTP 403 with a
 *       Go-client-parseable text body — consistent with the per-version
 *       block response produced by
 *       {@link GoCooldownResponseFactory}.</li>
 * </ol>
 *
 * @since 2.2.0
 */
public final class GoLatestHandler {

    /**
     * Max versions to evaluate when picking the fallback. Matches the
     * default {@code MetadataFilterService.DEFAULT_MAX_VERSIONS} so
     * operators see consistent cooldown-evaluation cost across adapters.
     */
    private static final int MAX_VERSIONS_TO_EVALUATE = 50;

    /**
     * TTL-cached, single-flighted loader for the {@code @latest} base
     * document — and, via the same instance, the sibling {@code @v/list}
     * fallback fetch, so both share one cache entry and one single-flight
     * gate per module (WS4-go.2).
     */
    private final GoMetadataBaseLoader baseLoader;

    /**
     * Cooldown service for block evaluation.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector for release-date lookups.
     */
    private final CooldownInspector inspector;

    /**
     * Repository type (e.g. {@code "go"}, {@code "go-proxy"}).
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Path detector for {@code /@latest} endpoints.
     */
    private final GoLatestMetadataRequestDetector detector;

    /**
     * JSON parser for {@code @latest} bodies.
     */
    private final GoLatestMetadataParser parser;

    /**
     * Local filter (pure, no I/O).
     */
    private final GoLatestMetadataFilter filter;

    /**
     * JSON serialiser for the rewritten body.
     */
    private final GoLatestMetadataRewriter rewriter;

    /**
     * Constructor.
     *
     * @param baseLoader TTL-cached, single-flighted base-document loader
     * @param cooldown Cooldown evaluation service
     * @param inspector Cooldown inspector for release-date lookups
     * @param repoType Repository type identifier (e.g. {@code "go"})
     * @param repoName Repository name
     */
    public GoLatestHandler(
        final GoMetadataBaseLoader baseLoader,
        final CooldownService cooldown,
        final CooldownInspector inspector,
        final String repoType,
        final String repoName
    ) {
        this.baseLoader = baseLoader;
        this.cooldown = cooldown;
        this.inspector = inspector;
        this.repoType = repoType;
        this.repoName = repoName;
        this.detector = new GoLatestMetadataRequestDetector();
        this.parser = new GoLatestMetadataParser();
        this.filter = new GoLatestMetadataFilter();
        this.rewriter = new GoLatestMetadataRewriter();
    }

    /**
     * Whether the handler should intercept the given path.
     *
     * @param path Request path
     * @return true for {@code /<module>/@latest} paths with a non-empty module
     */
    public boolean matches(final String path) {
        return this.detector.isMetadataRequest(path)
            && this.detector.extractPackageName(path).isPresent();
    }

    /**
     * Handle a {@code /@latest} request with cooldown-aware fallback.
     *
     * @param line Request line (must be an {@code /@latest} path)
     * @param headers Inbound request headers, used to bind trace/client-ip
     *                context onto this thread's MDC before any async hop
     * @param user Authenticated user (for cooldown bookkeeping)
     * @return Future response
     */
    public CompletableFuture<Response> handle(
        final RequestLine line, final Headers headers, final String user
    ) {
        RequestContextHeaders.bindToMdc(headers);
        final AuditContext ctx = new AuditContext(
            MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP)
        );
        final String path = line.uri().getPath();
        final String module = this.detector.extractPackageName(path).orElseThrow(
            () -> new IllegalArgumentException("Not a @latest path: " + path)
        );
        return this.baseLoader.load(path, module).thenCompose(outcome -> {
            if (!outcome.isAvailable()) {
                // Nothing cached anywhere and upstream failed / returned
                // non-2xx — forward its status + body unchanged, no rewrite.
                return CompletableFuture.completedFuture(
                    ResponseBuilder.from(outcome.status())
                        .body(outcome.errorBody())
                        .build()
                );
            }
            return this.processUpstream(outcome.body(), module, user, ctx);
        });
    }

    /**
     * Process the resolved base document: pass-through when allowed,
     * rewrite when the version is blocked, 403 when nothing resolves.
     */
    private CompletableFuture<Response> processUpstream(
        final byte[] upstreamBytes,
        final String module,
        final String user,
        final AuditContext ctx
    ) {
        final GoLatestInfo info;
        try {
            info = this.parser.parse(upstreamBytes);
        } catch (final MetadataParseException ex) {
            EcsLogger.warn("com.auto1.pantera.http.cooldown")
                .message("Failed to parse @latest JSON — passing upstream body through")
                .eventCategory("web")
                .eventAction("latest_filter")
                .eventOutcome("success")
                .field("event.reason", "upstream_malformed")
                .field("repository.name", this.repoName)
                .field("package.name", module)
                .error(ex)
                .field("log.source", "application")
                .log();
            // A listing was still served (unfiltered fallback) — audit it.
            AuditLogger.resolutionDetailUnknown(
                ctx, this.repoType, this.repoName, module, user,
                "@latest parse fallback (unfiltered upstream bytes)"
            );
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", this.rewriter.contentType())
                    .body(upstreamBytes)
                    .build()
            );
        }
        return this.isBlocked(module, info.version(), user).thenCompose(blocked -> {
            if (!blocked) {
                AuditLogger.resolution(ctx, this.repoType, this.repoName, module, user, null);
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header("Content-Type", this.rewriter.contentType())
                        .body(upstreamBytes)
                        .build()
                );
            }
            EcsLogger.info("com.auto1.pantera.http.cooldown")
                .message("@latest version blocked by cooldown; resolving fallback")
                .eventCategory("web")
                .eventAction("latest_filter")
                .eventOutcome("success")
                .field("event.reason", "version_blocked")
                .field("repository.name", this.repoName)
                .field("package.name", module)
                .field("package.version", info.version())
                .field("log.source", "application")
                .log();
            return this.resolveFallback(info, module, user, ctx);
        });
    }

    /**
     * Fetch {@code @v/list}, evaluate every candidate, and emit a
     * rewritten {@code @latest} JSON for the highest non-blocked version.
     * Returns 403 when the list is empty or every candidate is blocked.
     */
    private CompletableFuture<Response> resolveFallback(
        final GoLatestInfo upstreamInfo,
        final String module,
        final String user,
        final AuditContext ctx
    ) {
        final String listPath = "/" + module + "/@v/list";
        return this.baseLoader.load(listPath, module)
            .thenApply(outcome -> outcome.isAvailable()
                // Shares the @v/list base cache + single-flight gate with
                // GoListHandler: a warm list needs no extra upstream call
                // here, and a cold miss coalesces with any concurrent
                // GoListHandler request for the same module.
                ? this.parseVersionList(outcome.body())
                : List.<String>of())
            .thenCompose(candidates -> this.pickHighestNonBlocked(candidates, module, user))
            .thenApply(pickedOpt -> {
                if (pickedOpt.isEmpty()) {
                    // No non-blocked fallback exists (or the list fetch
                    // failed) — the request still asked for a version
                    // resolution; audit the blocked upstream latest before
                    // the 403.
                    AuditLogger.resolution(
                        ctx, this.repoType, this.repoName, module, user,
                        List.of(upstreamInfo.version())
                    );
                    return this.allBlockedResponse(module);
                }
                final String picked = pickedOpt.get();
                if (picked.equals(upstreamInfo.version())) {
                    // Shouldn't happen (we got here because upstream was blocked),
                    // but guard against a race with the block being lifted.
                    AuditLogger.resolution(
                        ctx, this.repoType, this.repoName, module, user, null
                    );
                    return ResponseBuilder.ok()
                        .header("Content-Type", this.rewriter.contentType())
                        .body(this.rewriter.rewrite(upstreamInfo))
                        .build();
                }
                final GoLatestInfo rewritten = this.filter.updateLatest(upstreamInfo, picked);
                EcsLogger.info("com.auto1.pantera.http.cooldown")
                    .message("@latest rewritten to non-blocked fallback")
                    .eventCategory("web")
                    .eventAction("latest_filter")
                    .eventOutcome("success")
                    .field("repository.name", this.repoName)
                    .field("package.name", module)
                    .field("package.version", picked)
                    .field("log.source", "application")
                    .log();
                AuditLogger.resolution(
                    ctx, this.repoType, this.repoName, module, user,
                    List.of(upstreamInfo.version())
                );
                return ResponseBuilder.ok()
                    .header("Content-Type", this.rewriter.contentType())
                    .body(this.rewriter.rewrite(rewritten))
                    .build();
            });
    }

    /**
     * 403 response for "every version blocked" — Go-client-parseable text.
     */
    private Response allBlockedResponse(final String module) {
        EcsLogger.info("com.auto1.pantera.http.cooldown")
            .message("@latest has no non-blocked fallback — returning 403")
            .eventCategory("web")
            .eventAction("latest_filter")
            .eventOutcome("failure")
            .field("event.reason", "all_versions_blocked")
            .field("repository.name", this.repoName)
            .field("package.name", module)
            .field("log.source", "application")
            .log();
        return ResponseBuilder.forbidden()
            .header("X-Pantera-Cooldown", "all-blocked")
            .textBody(
                "All versions of '" + module
                    + "' are under cooldown; no fallback available."
            )
            .build();
    }

    /**
     * Parse a newline-delimited {@code @v/list} body into versions.
     * Mirrors {@code GoMetadataParser.parse()} but is local to avoid a
     * hard dependency on the SPI parser when it evolves independently.
     */
    private List<String> parseVersionList(final byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        final String body = new String(bytes, StandardCharsets.UTF_8);
        final String[] lines = body.split("\n", -1);
        final List<String> out = new ArrayList<>(lines.length);
        for (final String line : lines) {
            final String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * Evaluate every candidate for cooldown and return the highest
     * non-blocked version by Go's semver ordering. Evaluation is capped
     * at {@link #MAX_VERSIONS_TO_EVALUATE} newest candidates.
     */
    private CompletableFuture<java.util.Optional<String>> pickHighestNonBlocked(
        final List<String> candidates, final String module, final String user
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return CompletableFuture.completedFuture(java.util.Optional.empty());
        }
        // Sort descending (newest first) per Go semver semantics.
        final Comparator<String> semverDesc = VersionComparators.semver().reversed();
        final List<String> sorted = new ArrayList<>(candidates);
        sorted.sort(semverDesc);
        final List<String> bounded = sorted.size() > MAX_VERSIONS_TO_EVALUATE
            ? sorted.subList(0, MAX_VERSIONS_TO_EVALUATE)
            : sorted;
        // Evaluate each candidate in parallel, then pick the newest non-blocked.
        final List<CompletableFuture<Boolean>> futures = new ArrayList<>(bounded.size());
        for (final String version : bounded) {
            futures.add(this.isBlocked(module, version, user));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                for (int idx = 0; idx < bounded.size(); idx++) {
                    if (!futures.get(idx).join()) {
                        return java.util.Optional.of(bounded.get(idx));
                    }
                }
                return java.util.Optional.<String>empty();
            });
    }

    /**
     * Ask the cooldown service whether {@code version} is currently
     * blocked for this repo. Swallows per-version evaluation errors by
     * treating them as "not blocked" — a transient inspector failure
     * must not cascade to denying every Go resolution.
     */
    private CompletableFuture<Boolean> isBlocked(
        final String module, final String version, final String user
    ) {
        final CooldownRequest req = new CooldownRequest(
            this.repoType,
            this.repoName,
            module,
            version,
            user == null ? "go-latest" : user,
            Instant.now()
        );
        return this.cooldown.evaluate(req, this.inspector)
            .thenApply(result -> result.blocked())
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.http.cooldown")
                    .message("Cooldown evaluation failed; treating version as allowed")
                    .eventCategory("database")
                    .eventAction("cooldown_evaluate")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", module)
                    .field("package.version", version)
                    .error(err)
                    .field("log.source", "application")
                    .log();
                return false;
            });
    }

}
