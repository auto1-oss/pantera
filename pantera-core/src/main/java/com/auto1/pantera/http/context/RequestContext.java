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
package com.auto1.pantera.http.context;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.ThreadContext;

/**
 * Immutable per-request envelope carrying every ECS field Pantera emits,
 * plus the end-to-end {@link Deadline}.
 *
 * <p>Implements §3.3 of {@code docs/analysis/v2.2-target-architecture.md}.
 * Constructed once by the request-edge logging slice after auth resolution,
 * then bound to the Log4j2 {@link ThreadContext} for the lifetime of the
 * request via {@link #bindToMdc()}. {@code EcsLayout} reads the ECS keys
 * straight from the ThreadContext, so logs emitted anywhere on the request
 * path carry trace / transaction / user / client metadata without the caller
 * having to re-thread them.
 *
 * <p>Thread propagation across {@code CompletableFuture} boundaries is handled
 * by {@link ContextualExecutor} (§4.4). The {@code Deadline} is carried
 * through the record but deliberately <em>not</em> persisted in the
 * ThreadContext — it has no ECS key, and the next process / span would have
 * no way to interpret a nanosecond timestamp from a different JVM.
 *
 * @param traceId       ECS {@code trace.id} — from {@code ElasticApm.currentTransaction()}.
 *                      May be {@code null} before the APM transaction is created.
 * @param transactionId ECS {@code transaction.id} — APM transaction ID; {@code null}
 *                      if the APM agent is not attached.
 * @param spanId        ECS {@code span.id} — current span; {@code null} if none.
 * @param httpRequestId ECS {@code http.request.id} — unique per HTTP request
 *                      (X-Request-ID header, else a server-generated UUID).
 * @param userName      ECS {@code user.name} — always present; {@code "anonymous"}
 *                      when the request is unauthenticated. Never {@code null}.
 * @param clientIp      ECS {@code client.ip} — resolved via
 *                      XFF → X-Real-IP → remote-addr. May be {@code null}.
 * @param userAgent     ECS {@code user_agent.original} — raw User-Agent header.
 *                      May be {@code null}.
 * @param repoName      ECS {@code repository.name} — Pantera-specific field
 *                      naming the target repository (e.g. {@code "npm_group"}).
 * @param repoType      ECS {@code repository.type} — one of the adapter type
 *                      tokens (e.g. {@code "maven"}, {@code "npm"}). Nullable
 *                      for requests not yet resolved to a repo.
 * @param artifact      ECS {@code package.name} + {@code package.version} via
 *                      {@link ArtifactRef}. Use {@link ArtifactRef#EMPTY} for
 *                      metadata / index requests that carry no specific
 *                      artifact. Must never be {@code null}.
 * @param urlOriginal   ECS {@code url.original} — the URL as the client sent it.
 * @param urlPath       ECS {@code url.path} — path component only (no query).
 *                      May be {@code null}.
 * @param deadline      wall-clock deadline propagated across the request;
 *                      never emitted to ECS. Must not be {@code null}.
 * @since 2.2.0
 */
@SuppressWarnings("PMD.TooManyMethods")
public record RequestContext(
    String traceId,
    String transactionId,
    String spanId,
    String httpRequestId,
    String userName,
    String clientIp,
    String userAgent,
    String repoName,
    String repoType,
    ArtifactRef artifact,
    String urlOriginal,
    String urlPath,
    Deadline deadline
) {

    // ECS key constants — grouped here so both bindToMdc() and fromMdc()
    // reference a single source of truth. These match the keys EcsLayout emits.

    /** ECS key: {@code trace.id}. */
    public static final String KEY_TRACE_ID = "trace.id";
    /** ECS key: {@code transaction.id}. */
    public static final String KEY_TRANSACTION_ID = "transaction.id";
    /** ECS key: {@code span.id}. */
    public static final String KEY_SPAN_ID = "span.id";
    /** ECS key: {@code http.request.id}. */
    public static final String KEY_HTTP_REQUEST_ID = "http.request.id";
    /** ECS key: {@code user.name}. */
    public static final String KEY_USER_NAME = "user.name";
    /** ECS key: {@code client.ip}. */
    public static final String KEY_CLIENT_IP = "client.ip";
    /** ECS key: {@code user_agent.original}. */
    public static final String KEY_USER_AGENT = "user_agent.original";
    /** ECS key: {@code repository.name}. */
    public static final String KEY_REPO_NAME = "repository.name";
    /** ECS key: {@code repository.type}. */
    public static final String KEY_REPO_TYPE = "repository.type";
    /** ECS key: {@code package.name}. */
    public static final String KEY_PACKAGE_NAME = "package.name";
    /** ECS key: {@code package.version}. */
    public static final String KEY_PACKAGE_VERSION = "package.version";
    /** ECS key: {@code url.original}. */
    public static final String KEY_URL_ORIGINAL = "url.original";
    /** ECS key: {@code url.path}. */
    public static final String KEY_URL_PATH = "url.path";

    /** Default deadline applied by {@link #minimal(String, String, String, String)}. */
    private static final Duration DEFAULT_BUDGET = Duration.ofSeconds(30);

    /**
     * Backward-compatible 4-arg constructor retained so production call-sites
     * that were written against the WI-01 scaffold continue to compile
     * (e.g. {@code maven-adapter}, {@code pypi-adapter}, {@code go-adapter},
     * {@code composer-adapter} cached-proxy slices, and tests in this module).
     *
     * <p>Delegates to the canonical 13-arg constructor via
     * {@link #minimal(String, String, String, String)} — sets
     * {@code userName="anonymous"}, empty {@link ArtifactRef}, default 30s
     * deadline, and {@code null} for every other optional field.
     *
     * @param traceId       ECS {@code trace.id}, may be {@code null}
     * @param httpRequestId ECS {@code http.request.id}, may be {@code null}
     * @param repoName      ECS {@code repository.name}
     * @param urlOriginal   ECS {@code url.original}
     */
    public RequestContext(
        final String traceId, final String httpRequestId,
        final String repoName, final String urlOriginal
    ) {
        this(
            traceId, null, null, httpRequestId,
            "anonymous", null, null,
            repoName, null, ArtifactRef.EMPTY,
            urlOriginal, null,
            Deadline.in(DEFAULT_BUDGET)
        );
    }

    /**
     * Factory producing a context with safe defaults for optional fields:
     * {@code userName="anonymous"}, {@link ArtifactRef#EMPTY},
     * {@code Deadline.in(30 s)}, {@code null} for every other nullable field.
     *
     * <p>Used at the request edge when only the bare minimum ({@code trace.id},
     * {@code http.request.id}, {@code repository.name}, {@code url.original})
     * is known — subsequent layers enrich via {@link #withRepo(String, String, ArtifactRef)}.
     *
     * @param traceId       ECS {@code trace.id}, may be {@code null}
     * @param httpRequestId ECS {@code http.request.id}, may be {@code null}
     * @param repoName      ECS {@code repository.name}, may be {@code null}
     * @param urlOriginal   ECS {@code url.original}, may be {@code null}
     * @return a new, non-null {@link RequestContext}
     */
    public static RequestContext minimal(
        final String traceId, final String httpRequestId,
        final String repoName, final String urlOriginal
    ) {
        return new RequestContext(
            traceId, null, null, httpRequestId,
            "anonymous", null, null,
            repoName, null, ArtifactRef.EMPTY,
            urlOriginal, null,
            Deadline.in(DEFAULT_BUDGET)
        );
    }

    /**
     * Produce a copy with the repository identity and artifact reference
     * updated; every other field is preserved verbatim.
     *
     * <p>Called after the group resolver has identified the target member +
     * the artifact name parser has extracted the package identity from the URL.
     *
     * @param newRepoName  ECS {@code repository.name} for the enriched context
     * @param newRepoType  ECS {@code repository.type}
     * @param newArtifact  {@link ArtifactRef} carrying {@code package.name}
     *                     and {@code package.version}; never {@code null}
     *                     ({@link ArtifactRef#EMPTY} for metadata requests)
     * @return a new {@link RequestContext} instance
     */
    public RequestContext withRepo(
        final String newRepoName, final String newRepoType,
        final ArtifactRef newArtifact
    ) {
        return new RequestContext(
            this.traceId, this.transactionId, this.spanId, this.httpRequestId,
            this.userName, this.clientIp, this.userAgent,
            newRepoName, newRepoType, newArtifact == null ? ArtifactRef.EMPTY : newArtifact,
            this.urlOriginal, this.urlPath,
            this.deadline
        );
    }

    /**
     * Push every non-null ECS field into the Log4j2 {@link ThreadContext}
     * and return an {@link AutoCloseable} that restores the prior ThreadContext
     * on close.
     *
     * <p>Use in a try-with-resources at the request edge:
     * <pre>{@code
     *   try (AutoCloseable bound = ctx.bindToMdc()) {
     *       slice.response(...)
     *            .thenAccept(...);
     *   }
     * }</pre>
     *
     * <p>Contract:
     * <ul>
     *   <li>Only non-null fields are pushed — {@code null} maps to "no key"
     *       (never {@code put(key, null)}), so missing fields don't show up
     *       as empty strings in ECS logs.
     *   <li>Prior ThreadContext state is captured on entry and restored on
     *       close. Idempotent: double-close is a no-op.
     *   <li>The {@link Deadline} is <em>not</em> bound (it has no ECS key).
     * </ul>
     *
     * @return an {@link AutoCloseable} whose {@code close()} restores the
     *         ThreadContext snapshot taken on bind
     */
    public AutoCloseable bindToMdc() {
        final Map<String, String> prior = ThreadContext.getImmutableContext();
        putIfNotNull(KEY_TRACE_ID, this.traceId);
        putIfNotNull(KEY_TRANSACTION_ID, this.transactionId);
        putIfNotNull(KEY_SPAN_ID, this.spanId);
        putIfNotNull(KEY_HTTP_REQUEST_ID, this.httpRequestId);
        putIfNotNull(KEY_USER_NAME, this.userName);
        putIfNotNull(KEY_CLIENT_IP, this.clientIp);
        putIfNotNull(KEY_USER_AGENT, this.userAgent);
        putIfNotNull(KEY_REPO_NAME, this.repoName);
        putIfNotNull(KEY_REPO_TYPE, this.repoType);
        if (this.artifact != null && !this.artifact.isEmpty()) {
            putIfNotNull(KEY_PACKAGE_NAME, this.artifact.name());
            putIfNotNull(KEY_PACKAGE_VERSION, this.artifact.version());
        }
        putIfNotNull(KEY_URL_ORIGINAL, this.urlOriginal);
        putIfNotNull(KEY_URL_PATH, this.urlPath);
        return new MdcRestore(prior);
    }

    /**
     * Rebuild a {@link RequestContext} from the current Log4j2 {@link ThreadContext}.
     *
     * <p>Used on thread hops before {@link ContextualExecutor} is in place, or
     * in logger utilities that need the current ECS state without threading
     * the record through every method signature. Missing keys become
     * {@code null} (never throw). The {@link Deadline} is lossy — ThreadContext
     * stores no expiry value — so a fresh {@code Deadline.in(30 s)} is
     * synthesised as a conservative default.
     *
     * @return a new {@link RequestContext} populated from the current
     *         ThreadContext; never {@code null}
     */
    public static RequestContext fromMdc() {
        final String pkgName = ThreadContext.get(KEY_PACKAGE_NAME);
        final String pkgVersion = ThreadContext.get(KEY_PACKAGE_VERSION);
        final ArtifactRef art;
        if (pkgName == null || pkgName.isEmpty()) {
            art = ArtifactRef.EMPTY;
        } else {
            art = new ArtifactRef(pkgName, pkgVersion == null ? "" : pkgVersion);
        }
        return new RequestContext(
            ThreadContext.get(KEY_TRACE_ID),
            ThreadContext.get(KEY_TRANSACTION_ID),
            ThreadContext.get(KEY_SPAN_ID),
            ThreadContext.get(KEY_HTTP_REQUEST_ID),
            ThreadContext.get(KEY_USER_NAME),
            ThreadContext.get(KEY_CLIENT_IP),
            ThreadContext.get(KEY_USER_AGENT),
            ThreadContext.get(KEY_REPO_NAME),
            ThreadContext.get(KEY_REPO_TYPE),
            art,
            ThreadContext.get(KEY_URL_ORIGINAL),
            ThreadContext.get(KEY_URL_PATH),
            Deadline.in(DEFAULT_BUDGET)
        );
    }

    /** Small helper — skip {@link ThreadContext#put} when {@code value} is null. */
    private static void putIfNotNull(final String key, final String value) {
        if (value != null) {
            ThreadContext.put(key, value);
        }
    }

    /**
     * Package identity within a request. {@link #EMPTY} signals
     * "no specific package" — used for metadata / index requests
     * ({@code /-/package/...}, {@code /maven-metadata.xml}, etc).
     *
     * @param name    ECS {@code package.name}; {@code ""} for empty
     * @param version ECS {@code package.version}; {@code ""} for empty / metadata
     */
    public record ArtifactRef(String name, String version) {

        /** Sentinel for "no artifact resolved yet" / metadata requests. */
        public static final ArtifactRef EMPTY = new ArtifactRef("", "");

        /** @return {@code true} if this is {@link #EMPTY} (name is empty). */
        public boolean isEmpty() {
            return this.name.isEmpty();
        }
    }

    /**
     * AutoCloseable handle returned by {@link #bindToMdc()}. Restores the
     * ThreadContext snapshot taken at bind time on {@link #close()}.
     * Idempotent — double-close is a no-op.
     */
    private static final class MdcRestore implements AutoCloseable {

        private final Map<String, String> prior;
        private boolean closed;

        private MdcRestore(final Map<String, String> priorCtx) {
            // Defensive copy — the immutable map returned by
            // ThreadContext.getImmutableContext() is safe, but we copy anyway
            // to avoid holding a reference into a concurrent impl.
            this.prior = new HashMap<>(priorCtx);
            this.closed = false;
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            ThreadContext.clearMap();
            if (!this.prior.isEmpty()) {
                ThreadContext.putAll(this.prior);
            }
        }
    }
}
