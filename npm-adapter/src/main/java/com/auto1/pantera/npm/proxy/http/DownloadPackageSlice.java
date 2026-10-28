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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.auto1.pantera.npm.misc.MetadataETag;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.cooldown.metadata.AllVersionsBlockedException;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.body.JsonStringRewrite;
import com.auto1.pantera.npm.cooldown.NpmMetadataFilter;
import com.auto1.pantera.npm.misc.PackumentDecision;
import com.auto1.pantera.npm.misc.PackumentSkeleton;
import com.auto1.pantera.npm.cooldown.NpmMetadataParser;
import com.auto1.pantera.npm.cooldown.NpmMetadataRewriter;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import org.apache.commons.lang3.StringUtils;
import java.nio.charset.StandardCharsets;
import java.net.URL;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.StreamSupport;

/**
 * HTTP slice for download package requests.
 *
 * <p><b>Trace context contract.</b> Trace context (trace.id / span.id /
 * span.parent.id) is inherited from the {@code EcsLoggingSlice} MDC scope
 * set at request entry. Any async hop introduced in this slice MUST use
 * {@code ContextualExecutor.contextualize(...)} (or an equivalent MDC
 * capture-and-restore) to preserve trace.id across the executor
 * boundary — without it, log lines emitted from the worker thread
 * surface in Kibana with no trace correlation back to the originating
 * request.
 */
public final class DownloadPackageSlice implements Slice {

    /**
     * Filtered-metadata envelope variant: the cooldown verdict on a
     * packument's skeleton, shared by full, abbreviated and single-version
     * requests. Distinct from the pre-2.2.11 whole-document variants.
     */
    static final String SKELETON = "skeleton";

    /**
     * Path of every version's tarball URL in a packument.
     */
    private static final String TARBALL = "versions/*/dist/tarball";
    /**
     * NPM Proxy facade.
     */
    private final NpmProxy npm;

    /**
     * Package path helper.
     */
    private final PackagePath path;

    /**
     * Base URL for the repository (optional).
     */
    private final Optional<URL> baseUrl;

    /**
     * Cooldown metadata filtering service.
     */
    private final CooldownMetadataService cooldownMetadata;

    /**
     * Repository type.
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Resolves {@code GET}/{@code HEAD /<pkg>/<version-or-tag>} against the
     * (cooldown-filtered) packument.
     */
    private final VersionManifestResolver resolver;

    /**
     * @param npm NPM Proxy facade
     * @param path Package path helper
     */
    public DownloadPackageSlice(final NpmProxy npm, final PackagePath path) {
        this(npm, path, Optional.empty(), null, null, null);
    }

    /**
     * @param npm NPM Proxy facade
     * @param path Package path helper
     * @param baseUrl Base URL for the repository
     */
    public DownloadPackageSlice(final NpmProxy npm, final PackagePath path, final Optional<URL> baseUrl) {
        this(npm, path, baseUrl, null, null, null);
    }

    /**
     * @param npm NPM Proxy facade
     * @param path Package path helper
     * @param baseUrl Base URL for the repository
     * @param cooldownMetadata Cooldown metadata filtering service
     * @param repoType Repository type
     * @param repoName Repository name
     */
    public DownloadPackageSlice(
        final NpmProxy npm,
        final PackagePath path,
        final Optional<URL> baseUrl,
        final CooldownMetadataService cooldownMetadata,
        final String repoType,
        final String repoName
    ) {
        this.npm = npm;
        this.path = path;
        this.baseUrl = baseUrl;
        this.cooldownMetadata = cooldownMetadata;
        this.repoType = repoType;
        this.repoName = repoName;
        this.resolver = new VersionManifestResolver(npm, cooldownMetadata, repoType, repoName);
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        // Phase 10.5 profiler — total npm packument wall time per request.
        final long entryNs = System.nanoTime();
        // Captured synchronously (before the body.asBytesFuture() async hop
        // below) so the artifact.audit resolution record threaded into
        // CooldownMetadataService reflects THIS request's correlation
        // context rather than whatever (or nothing) the continuation's
        // worker thread has bound.
        com.auto1.pantera.http.log.RequestContextHeaders.bindToMdc(headers);
        final com.auto1.pantera.audit.AuditContext auditCtx = new com.auto1.pantera.audit.AuditContext(headers);
        final String owner = new com.auto1.pantera.http.headers.Login(headers).getValue();
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored -> {
            // P0.1: Check if client requests abbreviated format
            final boolean abbreviated = this.isAbbreviatedRequest(headers);

            // P0.2: Check for conditional request (If-None-Match)
            final Optional<String> clientETag = this.extractClientETag(headers);

            // URL-decode package name to handle scoped packages like @authn8%2fmcp-server -> @authn8/mcp-server
            final String rawPath = this.path.value(line.uri().getPath());
            final String rawPackageName = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);

            // Single-version / dist-tag reference: GET /<pkg>/<version> and
            // /<pkg>/<tag> (including /latest). Resolved from the filtered
            // packument so cooldown still applies, with the tarball URL
            // rewritten to the base the client addressed.
            final Optional<VersionManifestResolver.PackageRef> versionRef =
                VersionManifestResolver.parse(rawPackageName);
            if (versionRef.isPresent()) {
                return this.resolver.resolve(
                    versionRef.get().pkg(), versionRef.get().ref(),
                    this.getTarballPrefix(headers), this.vary(headers), clientETag, auditCtx, owner
                );
            }

            // MEMORY OPTIMIZATION: Use different paths for abbreviated vs full requests
            if (abbreviated) {
                // FAST PATH: Serve pre-computed abbreviated metadata directly
                // This avoids loading/parsing full metadata (38MB → 3MB, no JSON parsing)
                return this.serve(rawPackageName, headers, clientETag, auditCtx, owner, true);
            } else {
                // FULL PATH: Load and process full metadata
                return this.serve(rawPackageName, headers, clientETag, auditCtx, owner, false);
            }
        }).whenComplete((r, e) -> recordPhase("packument_total", entryNs))
        .exceptionally(error -> new FailureResponse("get_package", this.repoName)
            // CRITICAL: Convert exceptions to proper HTTP responses to prevent
            // "Parse Error: Expected HTTP/" errors in npm client. The cause is
            // logged; the body never carries exception text.
            .answer(error, headers, line.uri().getPath()));
    }
    

    /**
     * Serve a packument streamed from storage; the document is never read
     * into memory whole.
     *
     * <p>Without cooldown the stored document is streamed with its relative
     * tarball URLs rewritten in place ({@link JsonStringRewrite}). With
     * cooldown, the verdict is taken on the document's
     * {@link PackumentSkeleton}, which the filtered-metadata envelope caches
     * (the document is only scanned on an envelope miss), and the body is
     * streamed through the verdict's allow-list ({@link PackumentDecision#apply}):
     * a version the cached verdict has not seen is withheld, never served
     * unevaluated.</p>
     *
     * <p>An abbreviated request is answered from the stored abbreviated
     * document when there is one, otherwise with the full document (a
     * superset every npm client accepts).</p>
     */
    private CompletableFuture<Response> serve(
        final String packageName,
        final Headers headers,
        final Optional<String> clientETag,
        final AuditContext auditCtx,
        final String owner,
        final boolean abbreviated
    ) {
        final long metaNs = System.nanoTime();
        return this.npm.getPackageMetadataOnly(packageName)
            .doOnEvent((m, e) -> recordPhase("packument_metadata_fetch", metaNs))
            .map(Optional::of)
            .toSingle(Optional.empty())
            .to(SingleInterop.get())
            .toCompletableFuture()
            .thenCompose(
                metadata -> metadata.map(
                    meta -> this.serveStored(
                        new Stored(
                            packageName, meta,
                            abbreviated && meta.abbreviatedHash().isPresent()
                        ),
                        headers, clientETag, auditCtx, owner
                    )
                ).orElseGet(
                    () -> CompletableFuture.completedFuture(ResponseBuilder.notFound().build())
                )
            );
    }

    private CompletableFuture<Response> serveStored(
        final Stored stored,
        final Headers headers,
        final Optional<String> clientETag,
        final AuditContext auditCtx,
        final String owner
    ) {
        final String prefix = this.getTarballPrefix(headers);
        if (!this.cooldownActive()) {
            return this.raw(stored, prefix, headers, clientETag, Optional.of(auditCtx), owner);
        }
        return this.cooldownMetadata.filterMetadataLazily(
            this.repoType, this.repoName, DownloadPackageSlice.SKELETON, stored.name(),
            () -> this.open(stored).thenCompose(
                content -> content.map(body -> new PackumentSkeleton(body).bytes()).orElseGet(
                    () -> CompletableFuture.failedFuture(
                        new IllegalStateException("packument vanished: " + stored.name())
                    )
                )
            ),
            new NpmMetadataParser(), new NpmMetadataFilter(), new NpmMetadataRewriter(),
            auditCtx, owner
        ).handle(
            (skeleton, ex) -> {
                final CompletableFuture<Response> result;
                if (ex != null) {
                    result = this.filterFailed(stored, prefix, headers, clientETag, ex);
                } else if (skeleton.isEmpty()) {
                    result = this.raw(stored, prefix, headers, clientETag, Optional.empty(), owner);
                } else {
                    result = this.filtered(
                        stored, new PackumentDecision(skeleton.get()), skeleton.get(),
                        prefix, headers, clientETag
                    );
                }
                return result;
            }
        ).thenCompose(Function.identity());
    }

    /**
     * Cooldown could not decide. Every version under cooldown answers 403. A
     * cooldown backend failure (database error, timeout) serves the document
     * unfiltered, as before 2.2.11, so an outage of the cooldown store does not
     * break installs. Anything else answers 502: it came from evaluating the
     * stored document, whose content comes from upstream, and must never be a
     * way past cooldown.
     */
    private CompletableFuture<Response> filterFailed(
        final Stored stored,
        final String prefix,
        final Headers headers,
        final Optional<String> clientETag,
        final Throwable error
    ) {
        final CompletableFuture<Response> result;
        if (DownloadPackageSlice.allBlocked(error)) {
            EcsLogger.info("com.auto1.pantera.npm")
                .message("All versions blocked by cooldown")
                .eventCategory("database")
                .eventAction("all_versions_blocked")
                .field("package.name", stored.name())
                .field("log.source", "application")
                .log();
            result = CompletableFuture.completedFuture(
                ResponseBuilder.forbidden()
                    .header("X-Pantera-Cooldown", "all-blocked")
                    .jsonBody(
                        String.format(
                            "{\"error\":\"All versions of '%s' are under security cooldown. New packages must wait 7 days before installation.\",\"package\":\"%s\"}",
                            stored.name(), stored.name()
                        )
                    )
                    .build()
            );
        } else if (!DownloadPackageSlice.backendFailure(error)) {
            EcsLogger.warn("com.auto1.pantera.npm")
                .message("Stored packument cannot be evaluated for cooldown - refusing to serve it unfiltered")
                .eventCategory("database")
                .eventAction("filter_error")
                .eventOutcome("failure")
                .field("package.name", stored.name())
                .error(error)
                .field("log.source", "application")
                .log();
            result = CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.byCode(502))
                    .jsonBody(
                        String.format(
                            "{\"error\":\"Package metadata of '%s' could not be evaluated for cooldown\",\"package\":\"%s\"}",
                            stored.name(), stored.name()
                        )
                    )
                    .build()
            );
        } else {
            EcsLogger.warn("com.auto1.pantera.npm")
                .message("Cooldown filter error - falling back to unfiltered")
                .eventCategory("database")
                .eventAction("filter_error")
                .field("package.name", stored.name())
                .error(error)
                .field("log.source", "application")
                .log();
            result = this.raw(stored, prefix, headers, clientETag, Optional.empty(), "");
        }
        return result;
    }

    /**
     * Stream the stored document with only its relative tarball URLs
     * rewritten. Its ETag derives from the stored content hash.
     *
     * @param audit Context to emit the resolution audit with; empty when
     *  the cooldown service already emitted it
     */
    private CompletableFuture<Response> raw(
        final Stored stored,
        final String prefix,
        final Headers headers,
        final Optional<String> clientETag,
        final Optional<AuditContext> audit,
        final String owner
    ) {
        final Optional<String> etag = stored.hash().map(hash -> MetadataETag.derive(hash, prefix));
        final CompletableFuture<Response> result;
        if (etag.isPresent() && etag.equals(clientETag)) {
            audit.ifPresent(
                ctx -> AuditLogger.resolutionDetailUnknown(
                    ctx, this.repoType, this.repoName, stored.name(), owner,
                    "etag revalidation (304)"
                )
            );
            result = CompletableFuture.completedFuture(this.notModified(etag.get(), headers));
        } else {
            audit.ifPresent(
                ctx -> AuditLogger.resolution(
                    ctx, this.repoType, this.repoName, stored.name(), owner, List.of()
                )
            );
            result = this.open(stored).thenApply(
                content -> content.map(
                    body -> this.ok(
                        stored,
                        new JsonStringRewrite(
                            body,
                            Map.of(
                                DownloadPackageSlice.TARBALL,
                                url -> url.startsWith("/") ? prefix + url : url
                            )
                        ),
                        etag, headers
                    )
                ).orElseGet(() -> ResponseBuilder.notFound().build())
            );
        }
        return result;
    }

    /**
     * Stream the stored document through the cooldown verdict. Its ETag
     * derives from the stored content hash and the filtered skeleton, so it
     * changes when either the document or the verdict does.
     */
    private CompletableFuture<Response> filtered(
        final Stored stored,
        final PackumentDecision decision,
        final byte[] skeleton,
        final String prefix,
        final Headers headers,
        final Optional<String> clientETag
    ) {
        final String etag = MetadataETag.derive(
            new MetadataETag(
                stored.hash().orElse(stored.metadata().lastModified())
                    + ':' + new MetadataETag(skeleton).calculate()
            ).calculate(),
            prefix
        );
        final CompletableFuture<Response> result;
        if (clientETag.isPresent() && clientETag.get().equals(etag)) {
            result = CompletableFuture.completedFuture(this.notModified(etag, headers));
        } else {
            result = this.open(stored).thenApply(
                content -> content.map(
                    body -> this.ok(stored, decision.apply(body, prefix), Optional.of(etag), headers)
                ).orElseGet(() -> ResponseBuilder.notFound().build())
            );
        }
        return result;
    }

    private CompletableFuture<Optional<Content>> open(final Stored stored) {
        final io.reactivex.Maybe<Content> stream;
        if (stored.abbreviated()) {
            stream = this.npm.getAbbreviatedContentStream(stored.name());
        } else {
            stream = this.npm.getPackageContentStream(stored.name());
        }
        return stream.map(Optional::of)
            .toSingle(Optional.empty())
            .to(SingleInterop.get())
            .toCompletableFuture();
    }

    private Response ok(
        final Stored stored,
        final Content body,
        final Optional<String> etag,
        final Headers headers
    ) {
        final ResponseBuilder builder = ResponseBuilder.ok()
            .header(
                "Content-Type",
                stored.abbreviated()
                    ? "application/vnd.npm.install-v1+json; charset=utf-8"
                    : "application/json; charset=utf-8"
            )
            .header("Last-Modified", stored.metadata().lastModified());
        etag.ifPresent(tag -> builder.header("ETag", tag));
        return builder
            .header("Cache-Control", "public, max-age=300")
            .header("CDN-Cache-Control", "public, max-age=600")
            .varyHeader(this.vary(headers))
            .body(body)
            .build();
    }

    private Response notModified(final String etag, final Headers headers) {
        return ResponseBuilder.from(RsStatus.NOT_MODIFIED)
            .header("ETag", etag)
            .header("Cache-Control", "public, max-age=300")
            .varyHeader(this.vary(headers))
            .build();
    }

    /**
     * Whether a cooldown failure is a backend failure (the cooldown database
     * or a timeout), the only failures that may serve a packument unfiltered.
     * A parse failure, a JVM {@link Error} or anything unrecognised is not.
     *
     * @param error Failure
     * @return True when the cause chain holds a backend failure and no parse
     *  failure or {@link Error}
     */
    static boolean backendFailure(final Throwable error) {
        Throwable cause = error;
        boolean backend = false;
        boolean disqualified = false;
        while (cause != null) {
            if (cause instanceof Error
                || cause instanceof com.fasterxml.jackson.core.JsonProcessingException
                || cause instanceof com.auto1.pantera.cooldown.metadata.MetadataParseException) {
                disqualified = true;
            }
            if (cause instanceof java.sql.SQLException
                || cause instanceof java.util.concurrent.TimeoutException) {
                backend = true;
            }
            cause = cause.getCause();
        }
        return backend && !disqualified;
    }

    private static boolean allBlocked(final Throwable error) {
        Throwable cause = error;
        boolean blocked = false;
        while (cause != null && !blocked) {
            blocked = cause instanceof AllVersionsBlockedException;
            cause = cause.getCause();
        }
        return blocked;
    }

    /**
     * Stored packument being served.
     *
     * @param name Package name
     * @param metadata Stored {@code meta.meta}
     * @param abbreviated Whether the stored abbreviated document is served
     */
    private record Stored(
        String name,
        com.auto1.pantera.npm.proxy.model.NpmPackage.Metadata metadata,
        boolean abbreviated
    ) {
        /**
         * Stored SHA-256 of the served document.
         *
         * @return Hash, empty for entries stored before hashes were recorded
         */
        Optional<String> hash() {
            return this.abbreviated ? this.metadata.abbreviatedHash() : this.metadata.contentHash();
        }
    }

    /**
     * {@code Vary} header value for a response whose body embeds a base URL
     * derived (or potentially derivable) from {@code headers} — see
     * {@link ClientBaseUrl#varyHeaderValue()}.
     *
     * @param headers Request headers
     * @return Vary header value
     */
    private String vary(final Headers headers) {
        return new ClientBaseUrl(headers).varyHeaderValue();
    }

    /**
     * Client-facing prefix for tarball URLs, in precedence order: the base
     * stamped by {@code SliceByPath} for the repository the client actually
     * addressed (so a group member emits group URLs), then this repository's
     * configured {@code url:}, then the request's own origin.
     *
     * @param headers Request headers
     * @return Absolute URL prefix
     */
    private String getTarballPrefix(final Headers headers) {
        final String result;
        final Optional<String> stamped = new ClientBaseUrl(headers).stamped();
        if (stamped.isPresent()) {
            result = stamped.get();
        } else if (this.baseUrl.isPresent()) {
            result = this.baseUrl.get().toString();
        } else {
            result = this.assetPrefix(headers);
        }
        return result;
    }

    /**
     * Whether cooldown metadata filtering is wired for this slice. When true,
     * the served body is the filtered packument (blocked versions removed and
     * {@code dist-tags.latest} possibly re-pointed), so the ETag must be keyed
     * to the filtered bytes rather than to the immutable upstream content hash.
     *
     * @return true if a {@link CooldownMetadataService} and repo type are set
     */
    private boolean cooldownActive() {
        return this.cooldownMetadata != null && this.repoType != null;
    }

    /**
     * Check if client requests abbreviated manifest.
     * 
     * @param headers Request headers
     * @return True if Accept header contains abbreviated format
     */
    private boolean isAbbreviatedRequest(final Headers headers) {
        return StreamSupport.stream(headers.spliterator(), false)
            .anyMatch(h -> "Accept".equalsIgnoreCase(h.getKey())
                && h.getValue().contains("application/vnd.npm.install-v1+json"));
    }
    
    /**
     * Extract client ETag from If-None-Match header.
     * 
     * @param headers Request headers
     * @return Optional ETag value
     */
    private Optional<String> extractClientETag(final Headers headers) {
        return StreamSupport.stream(headers.spliterator(), false)
            .filter(h -> "If-None-Match".equalsIgnoreCase(h.getKey()))
            .map(Header::getValue)
            .map(etag -> etag.startsWith("W/") ? etag.substring(2) : etag)
            .map(etag -> etag.replaceAll("\"", "")) // Remove quotes
            .findFirst();
    }

    /**
     * Phase 10.5 profiler — emit per-phase histogram tagged by repo so the
     * npm cold-cache wall can be decomposed without bringing
     * {@link com.auto1.pantera.http.cache.BaseCachedProxySlice} into the
     * structurally-different npm path. Repo name may be null in legacy
     * test ctors (no cooldownMetadata wiring) — guard with a label fallback.
     */
    private void recordPhase(final String phase, final long startNs) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            final String label = this.repoName == null ? "npm_proxy_unknown" : this.repoName;
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordProxyPhaseDuration(label, phase, System.nanoTime() - startNs);
        }
    }

    /**
     * Generates asset base reference from the request's own origin, honouring
     * reverse-proxy forwarding headers only when the DB-backed {@code
     * trust_forwarded_headers} admin setting is enabled (env fallback
     * {@code PANTERA_TRUST_FORWARDED_HEADERS}, default {@code false});
     * otherwise the origin is derived from the raw {@code Host} header,
     * itself gated by the {@code client_base_host_allowlist} setting (env
     * fallback {@code PANTERA_CLIENT_BASE_HOST_ALLOWLIST}) -- see {@link
     * ClientBaseUrl#origin()}.
     * @param headers Request headers
     * @return Asset base reference
     */
    private String assetPrefix(final Headers headers) {
        final String origin = new ClientBaseUrl(headers).origin();
        final String result;
        if (StringUtils.isEmpty(this.path.prefix())) {
            result = origin;
        } else {
            result = String.format("%s/%s", origin, this.path.prefix());
        }
        return result;
    }
}
