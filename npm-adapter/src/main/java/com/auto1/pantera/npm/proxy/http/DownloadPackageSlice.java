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

import com.auto1.pantera.asto.Concatenation;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.auto1.pantera.npm.proxy.json.ClientContent;
import com.auto1.pantera.npm.misc.AbbreviatedMetadata;
import com.auto1.pantera.npm.misc.MetadataETag;
import com.auto1.pantera.npm.misc.MetadataEnhancer;
import com.auto1.pantera.npm.misc.StreamingJsonTransformer;
import com.auto1.pantera.npm.misc.ByteLevelUrlTransformer;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.cooldown.metadata.AllVersionsBlockedException;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.npm.cooldown.NpmMetadataParser;
import com.auto1.pantera.npm.cooldown.NpmMetadataFilter;
import com.auto1.pantera.npm.cooldown.NpmMetadataRewriter;
import com.auto1.pantera.asto.rx.RxFuture;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import org.apache.commons.lang3.StringUtils;
import javax.json.Json;
import javax.json.JsonObject;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
        final com.auto1.pantera.audit.AuditContext auditCtx = new com.auto1.pantera.audit.AuditContext(
            org.slf4j.MDC.get(com.auto1.pantera.http.log.EcsMdc.TRACE_ID),
            org.slf4j.MDC.get(com.auto1.pantera.http.log.EcsMdc.CLIENT_IP)
        );
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
                    this.getTarballPrefix(headers), clientETag, auditCtx, owner
                );
            }

            // MEMORY OPTIMIZATION: Use different paths for abbreviated vs full requests
            if (abbreviated) {
                // FAST PATH: Serve pre-computed abbreviated metadata directly
                // This avoids loading/parsing full metadata (38MB → 3MB, no JSON parsing)
                return this.serveAbbreviated(rawPackageName, headers, clientETag, auditCtx, owner);
            } else {
                // FULL PATH: Load and process full metadata
                return this.serveFull(rawPackageName, headers, clientETag, auditCtx, owner);
            }
        }).whenComplete((r, e) -> recordPhase("packument_total", entryNs))
        .exceptionally(error -> {
            // CRITICAL: Convert exceptions to proper HTTP responses to prevent
            // "Parse Error: Expected HTTP/" errors in npm client.
            // Without this, exceptions propagate up and Vert.x closes the connection
            // without sending HTTP headers.
            final Throwable cause = unwrapException(error);
            EcsLogger.error("com.auto1.pantera.npm")
                .message("Error processing package request")
                .eventCategory("web")
                .eventAction("get_package")
                .eventOutcome("failure")
                .field("url.path", line.uri().getPath())
                .error(cause)
                .field("log.source", "application")
                .log();
            
            // A breaker fast-fail keeps its marker so the group resolver
            // can skip this member without convicting it.
            if (cause instanceof com.auto1.pantera.http.UpstreamCircuitOpenException circuit) {
                final ResponseBuilder rb = ResponseBuilder
                    .from(com.auto1.pantera.http.RsStatus.byCode(502))
                    .header(com.auto1.pantera.http.UpstreamCircuitOpenException.HEADER, "true")
                    .jsonBody("{\"error\":\"Upstream circuit breaker is open\"}");
                if (circuit.retryAfterSeconds() > 0) {
                    rb.header("Retry-After", Long.toString(circuit.retryAfterSeconds()));
                }
                return rb.build();
            }
            // Check if it's an HTTP exception with a specific status
            if (cause instanceof com.auto1.pantera.http.PanteraHttpException) {
                final com.auto1.pantera.http.PanteraHttpException httpEx = 
                    (com.auto1.pantera.http.PanteraHttpException) cause;
                return ResponseBuilder.from(httpEx.status())
                    .jsonBody(String.format(
                        "{\"error\":\"%s\"}",
                        httpEx.getMessage() != null ? httpEx.getMessage() : "Upstream error"
                    ))
                    .build();
            }
            
            // Generic 502 Bad Gateway for upstream errors
            return ResponseBuilder.from(RsStatus.byCode(502))
                .jsonBody(String.format(
                    "{\"error\":\"Upstream error: %s\"}",
                    cause.getMessage() != null ? cause.getMessage() : "Unknown error"
                ))
                .build();
        });
    }
    
    /**
     * Unwrap CompletionException to get the root cause.
     */
    private static Throwable unwrapException(final Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Serve abbreviated metadata using pre-computed cached version.
     * MEMORY OPTIMIZATION: ~90% memory reduction for npm install requests.
     * 
     * COOLDOWN: If cooldown is enabled, we must apply filtering even to abbreviated
     * metadata. This requires loading abbreviated bytes and filtering, but still
     * avoids full JSON parsing since abbreviated is much smaller (~3MB vs 38MB).
     */
    private CompletableFuture<Response> serveAbbreviated(
        final String packageName,
        final Headers headers,
        final Optional<String> clientETag,
        final com.auto1.pantera.audit.AuditContext auditCtx,
        final String owner
    ) {
        // Phase 10.5: time the metadata-only fetch (drives upstream when cache miss).
        final long metaNs = System.nanoTime();
        return this.npm.getPackageMetadataOnly(packageName)
            .doOnEvent((m, e) -> recordPhase("packument_metadata_fetch", metaNs))
            .flatMap(metadata -> {
                // PERF: Early 304 exit — skip content loading when the derived
                // ETag matches. Valid ONLY when cooldown filtering is inactive:
                // the derived ETag folds the immutable upstream hash, which does
                // not change when a version is blocked/unblocked. With cooldown
                // active we fall through and let buildAbbreviatedResponse
                // (filtered=true) compute the ETag from the filtered bytes, so a
                // block-state change busts the client cache instead of returning
                // a stale 304.
                if (!this.cooldownActive() && clientETag.isPresent()
                    && metadata.abbreviatedHash().isPresent()) {
                    final String tarballPrefix = this.getTarballPrefix(headers);
                    final String derivedEtag = MetadataETag.derive(
                        metadata.abbreviatedHash().get(), tarballPrefix
                    );
                    if (clientETag.get().equals(derivedEtag)) {
                        // Taxonomy: a 304 revalidation is still a metadata
                        // listing view — audit it. The filter never runs on
                        // this path so the filtered-version detail is unknown.
                        com.auto1.pantera.audit.AuditLogger.resolutionDetailUnknown(
                            auditCtx, this.repoType, this.repoName, packageName,
                            owner, "etag revalidation (304)"
                        );
                        return io.reactivex.Maybe.just(
                            ResponseBuilder.from(RsStatus.NOT_MODIFIED)
                                .header("ETag", derivedEtag)
                                .header("Cache-Control", "public, max-age=300")
                                .build()
                        );
                    }
                }
                // Try to get pre-computed abbreviated content
                return this.npm.getAbbreviatedContentStream(packageName)
                    .flatMap(abbreviatedStream -> {
                        final long abbrevSize = abbreviatedStream.size().orElse(-1L);
                        return Concatenation.withSize(abbreviatedStream, abbrevSize)
                            .single()
                            .map(buf -> new Remaining(buf).bytes())
                            .toMaybe()
                            .flatMap(abbreviatedBytes -> {
                                // COOLDOWN: Apply filtering if enabled
                                if (this.cooldownMetadata != null && this.repoType != null) {
                                    return this.applyAbbreviatedCooldown(
                                        abbreviatedBytes, packageName, metadata, headers, clientETag,
                                        auditCtx, owner
                                    );
                                }
                                // No cooldown wired - serve directly; still a
                                // metadata listing view, audit with no filtering.
                                com.auto1.pantera.audit.AuditLogger.resolution(
                                    auditCtx, this.repoType, this.repoName, packageName,
                                    owner, java.util.List.of()
                                );
                                return io.reactivex.Maybe.just(
                                    this.buildAbbreviatedResponse(abbreviatedBytes, metadata, headers, false, clientETag)
                                );
                            });
                    })
                    // Fall back to full metadata if abbreviated not available
                    // This can happen for legacy cached data before abbreviated was added
                    .switchIfEmpty(io.reactivex.Maybe.defer(() ->
                        this.npm.getPackageContentStream(packageName).flatMap(contentStream -> {
                            // OPTIMIZATION: Use size from Content when available for pre-allocation
                            final long contentSize = contentStream.size().orElse(-1L);
                            return Concatenation.withSize(contentStream, contentSize)
                                .single()
                                .map(buf -> new Remaining(buf).bytes())
                                .toMaybe()
                                .flatMap(rawBytes -> {
                                    // Apply cooldown filtering to full metadata too
                                    if (this.cooldownMetadata != null && this.repoType != null) {
                                        return this.applyFullMetadataCooldown(
                                            rawBytes, packageName, metadata, headers, clientETag,
                                            auditCtx, owner
                                        );
                                    }
                                    com.auto1.pantera.audit.AuditLogger.resolution(
                                        auditCtx, this.repoType, this.repoName, packageName,
                                        owner, java.util.List.of()
                                    );
                                    return io.reactivex.Maybe.just(
                                        this.buildResponse(rawBytes, metadata, headers, true, false, clientETag)
                                    );
                                });
                        })
                    ));
            })
            .toSingle(ResponseBuilder.notFound().build())
            .to(SingleInterop.get())
            .toCompletableFuture();
    }

    /**
     * Apply cooldown filtering to abbreviated metadata.
     *
     * Abbreviated metadata contains the "time" field with release dates
     * (added for pnpm compatibility in AbbreviatedMetadata.generate()).
     * CooldownMetadataService.filterMetadata() handles parsing and date extraction
     * internally via NpmMetadataParser which implements ReleaseDateProvider.
     * No need to pre-parse here - that would be redundant.
     */
    private io.reactivex.Maybe<Response> applyAbbreviatedCooldown(
        final byte[] abbreviatedBytes,
        final String packageName,
        final com.auto1.pantera.npm.proxy.model.NpmPackage.Metadata metadata,
        final Headers headers,
        final Optional<String> clientETag,
        final com.auto1.pantera.audit.AuditContext auditCtx,
        final String owner
    ) {
        // filterMetadata() parses JSON once and extracts release dates via ReleaseDateProvider
        // No need to pre-parse - that would double the parsing overhead
        final CompletableFuture<Response> filterFuture = this.applyFilterAndBuildResponse(
            abbreviatedBytes, packageName, metadata, headers, clientETag, auditCtx, owner
        );
        return RxFuture.maybe(filterFuture);
    }

    /**
     * Apply cooldown filtering to full metadata (fallback when abbreviated not available).
     * Full metadata contains the "time" field. CooldownMetadataService handles parsing.
     */
    private io.reactivex.Maybe<Response> applyFullMetadataCooldown(
        final byte[] fullBytes,
        final String packageName,
        final com.auto1.pantera.npm.proxy.model.NpmPackage.Metadata metadata,
        final Headers headers,
        final Optional<String> clientETag,
        final com.auto1.pantera.audit.AuditContext auditCtx,
        final String owner
    ) {
        final CompletableFuture<Response> filterFuture = this.cooldownMetadata.filterMetadata(
            this.repoType,
            this.repoName,
            packageName,
            fullBytes,
            new NpmMetadataParser(),
            new NpmMetadataFilter(),
            new NpmMetadataRewriter(),
            auditCtx,
            owner
        ).handle((filtered, ex) -> {
            if (ex != null) {
                Throwable cause = ex;
                while (cause != null) {
                    if (cause instanceof AllVersionsBlockedException) {
                        EcsLogger.info("com.auto1.pantera.npm")
                            .message("All versions blocked by cooldown (full fallback)")
                            .eventCategory("database")
                            .eventAction("all_versions_blocked")
                            .field("package.name", packageName)
                            .field("log.source", "application")
                            .log();
                        final String json = String.format(
                            "{\"error\":\"All versions of '%s' are under security cooldown. New packages must wait 7 days before installation.\",\"package\":\"%s\"}",
                            packageName, packageName
                        );
                        return ResponseBuilder.forbidden()
                            .jsonBody(json)
                            .build();
                    }
                    cause = cause.getCause();
                }
                EcsLogger.warn("com.auto1.pantera.npm")
                    .message("Cooldown filter error (full fallback) - serving unfiltered")
                    .eventCategory("database")
                    .eventAction("filter_error")
                    .field("package.name", packageName)
                    .error(ex)
                    .field("log.source", "application")
                    .log();
                return this.buildResponse(fullBytes, metadata, headers, true, false, clientETag);
            }
            return this.buildResponse(filtered, metadata, headers, true, true, clientETag);
        });
        return RxFuture.maybe(filterFuture);
    }

    /**
     * Apply cooldown filtering and build abbreviated response.
     * CooldownMetadataService handles JSON parsing and release date extraction internally.
     * Release dates are sourced from the canonical {@code PublishDateRegistry}
     * (via {@code RegistryBackedInspector}), populated from upstream metadata.
     */
    private CompletableFuture<Response> applyFilterAndBuildResponse(
        final byte[] abbreviatedBytes,
        final String packageName,
        final com.auto1.pantera.npm.proxy.model.NpmPackage.Metadata metadata,
        final Headers headers,
        final Optional<String> clientETag,
        final com.auto1.pantera.audit.AuditContext auditCtx,
        final String owner
    ) {
        return this.cooldownMetadata.filterMetadata(
            this.repoType,
            this.repoName,
            packageName,
            abbreviatedBytes,
            new NpmMetadataParser(),
            new NpmMetadataFilter(),
            new NpmMetadataRewriter(),
            auditCtx,
            owner
        ).handle((filtered, ex) -> {
                if (ex != null) {
                    Throwable cause = ex;
                    while (cause != null) {
                        if (cause instanceof AllVersionsBlockedException) {
                            EcsLogger.info("com.auto1.pantera.npm")
                                .message("All versions blocked by cooldown (abbreviated)")
                                .eventCategory("database")
                                .eventAction("all_versions_blocked")
                                .field("package.name", packageName)
                                .field("log.source", "application")
                                .log();
                            final String json = String.format(
                                "{\"error\":\"All versions of '%s' are under security cooldown. New packages must wait 7 days before installation.\",\"package\":\"%s\"}",
                                packageName, packageName
                            );
                            return ResponseBuilder.forbidden()
                                .jsonBody(json)
                                .build();
                        }
                        cause = cause.getCause();
                    }
                    EcsLogger.warn("com.auto1.pantera.npm")
                        .message("Cooldown filter error (abbreviated) - falling back to unfiltered")
                        .eventCategory("database")
                        .eventAction("filter_error")
                        .field("package.name", packageName)
                        .error(ex)
                        .field("log.source", "application")
                        .log();
                    return this.buildAbbreviatedResponse(abbreviatedBytes, metadata, headers, false, clientETag);
                }
                // Success - build response with filtered abbreviated metadata
                return this.buildAbbreviatedResponse(filtered, metadata, headers, true, clientETag);
            });
    }

    /**
     * Serve full metadata with cooldown filtering support.
     */
    private CompletableFuture<Response> serveFull(
        final String packageName,
        final Headers headers,
        final Optional<String> clientETag,
        final com.auto1.pantera.audit.AuditContext auditCtx,
        final String owner
    ) {
        return this.npm.getPackageMetadataOnly(packageName)
            .flatMap(metadata -> {
                // PERF: Early 304 exit — skip content loading when the derived
                // ETag matches. Valid ONLY when cooldown filtering is inactive
                // (see serveAbbreviated): the derived ETag ignores the filtered
                // output, so with cooldown active we fall through to
                // buildResponse (filtered=true) to key the ETag to the served
                // filtered bytes.
                if (!this.cooldownActive() && clientETag.isPresent()
                    && metadata.contentHash().isPresent()) {
                    final String tarballPrefix = this.getTarballPrefix(headers);
                    final String derivedEtag = MetadataETag.derive(
                        metadata.contentHash().get(), tarballPrefix
                    );
                    if (clientETag.get().equals(derivedEtag)) {
                        // Taxonomy: a 304 revalidation is still a metadata
                        // listing view — audit it. The filter never runs on
                        // this path so the filtered-version detail is unknown.
                        com.auto1.pantera.audit.AuditLogger.resolutionDetailUnknown(
                            auditCtx, this.repoType, this.repoName, packageName,
                            owner, "etag revalidation (304)"
                        );
                        return io.reactivex.Maybe.just(
                            ResponseBuilder.from(RsStatus.NOT_MODIFIED)
                                .header("ETag", derivedEtag)
                                .header("Cache-Control", "public, max-age=300")
                                .build()
                        );
                    }
                }
                return this.npm.getPackageContentStream(packageName).flatMap(contentStream -> {
                    // OPTIMIZATION: Use size from Content when available for pre-allocation
                    final long contentSize = contentStream.size().orElse(-1L);
                    return Concatenation.withSize(contentStream, contentSize)
                        .single()
                        .map(buf -> new Remaining(buf).bytes())
                        .toMaybe()
                        .flatMap(rawBytes -> {
                            // Apply cooldown filtering if available
                            if (this.cooldownMetadata != null && this.repoType != null) {
                                final CompletableFuture<Response> filterFuture =
                                    this.cooldownMetadata.filterMetadata(
                                        this.repoType,
                                        this.repoName,
                                        packageName,
                                        rawBytes,
                                        new NpmMetadataParser(),
                                        new NpmMetadataFilter(),
                                        new NpmMetadataRewriter(),
                                        auditCtx,
                                        owner
                                    ).handle((filtered, ex) -> {
                                        if (ex != null) {
                                            Throwable cause = ex;
                                            while (cause != null) {
                                                if (cause instanceof AllVersionsBlockedException) {
                                                    EcsLogger.info("com.auto1.pantera.npm")
                                                        .message("All versions blocked by cooldown")
                                                        .eventCategory("database")
                                                        .eventAction("all_versions_blocked")
                                                        .field("package.name", packageName)
                                                        .field("log.source", "application")
                                                        .log();
                                                    final String json = String.format(
                                                        "{\"error\":\"All versions of '%s' are under security cooldown. New packages must wait 7 days before installation.\",\"package\":\"%s\"}",
                                                        packageName, packageName
                                                    );
                                                    return ResponseBuilder.forbidden()
                                                        .jsonBody(json)
                                                        .build();
                                                }
                                                cause = cause.getCause();
                                            }
                                            EcsLogger.warn("com.auto1.pantera.npm")
                                                .message("Cooldown filter error - falling back to unfiltered")
                                                .eventCategory("database")
                                                .eventAction("filter_error")
                                                .field("package.name", packageName)
                                                .error(ex)
                                                .field("log.source", "application")
                                                .log();
                                            return this.buildResponse(rawBytes, metadata, headers, false, false, clientETag);
                                        }
                                        return this.buildResponse(filtered, metadata, headers, false, true, clientETag);
                                    });
                                return RxFuture.maybe(filterFuture);
                            }
                            // No cooldown wired - serve directly; still a
                            // metadata listing view, audit with no filtering.
                            com.auto1.pantera.audit.AuditLogger.resolution(
                                auditCtx, this.repoType, this.repoName, packageName,
                                owner, java.util.List.of()
                            );
                            return io.reactivex.Maybe.just(
                                this.buildResponse(rawBytes, metadata, headers, false, false, clientETag)
                            );
                        });
                });
            })
            .toSingle(ResponseBuilder.notFound().build())
            .to(SingleInterop.get())
            .toCompletableFuture();
    }

    /**
     * Build response from pre-computed abbreviated metadata.
     * MEMORY EFFICIENT: Uses byte-level URL transformation - no JSON parsing.
     */
    private Response buildAbbreviatedResponse(
        final byte[] abbreviatedBytes,
        final com.auto1.pantera.npm.proxy.model.NpmPackage.Metadata metadata,
        final Headers headers,
        final boolean filtered,
        final Optional<String> clientETag
    ) {
        final String tarballPrefix = this.getTarballPrefix(headers);
        // A filtered body keys its ETag to the served bytes (they change on
        // block/unblock); a raw body keeps the fast derive() from the stored
        // abbreviated hash (~100 bytes to hash, ~1000x faster than SHA-256 of
        // the full 3-5MB content).
        final String etag = filtered
            ? this.filteredEtag(abbreviatedBytes, tarballPrefix)
            : this.rawAbbreviatedEtag(metadata, abbreviatedBytes, tarballPrefix);
        // Check for 304 Not Modified BEFORE URL transformation
        if (clientETag.isPresent() && clientETag.get().equals(etag)) {
            return ResponseBuilder.from(RsStatus.NOT_MODIFIED)
                .header("ETag", etag)
                .header("Cache-Control", "public, max-age=300")
                .build();
        }
        // Only transform bytes when we actually need to send them
        final ByteLevelUrlTransformer transformer = new ByteLevelUrlTransformer();
        final byte[] transformedBytes = transformer.transform(abbreviatedBytes, tarballPrefix);
        final Content streamedContent = new Content.From(
            Flowable.fromArray(ByteBuffer.wrap(transformedBytes))
        );
        return ResponseBuilder.ok()
            .header("Content-Type", "application/vnd.npm.install-v1+json; charset=utf-8")
            .header("Last-Modified", metadata.lastModified())
            .header("ETag", etag)
            .header("Cache-Control", "public, max-age=300")
            .header("CDN-Cache-Control", "public, max-age=600")
            .body(streamedContent)
            .build();
    }

    /**
     * Build HTTP response from metadata bytes.
     * MEMORY OPTIMIZATION: Uses streaming JSON transformation for URL rewriting.
     */
    private Response buildResponse(
        final byte[] rawBytes,
        final com.auto1.pantera.npm.proxy.model.NpmPackage.Metadata metadata,
        final Headers headers,
        final boolean abbreviated,
        final boolean filtered,
        final Optional<String> clientETag
    ) {
        try {
            final String tarballPrefix = this.getTarballPrefix(headers);
            // For full metadata requests (abbreviated=false), we can skip JSON parsing
            if (!abbreviated) {
                // A filtered body keys its ETag to the served bytes (they change
                // on block/unblock); a raw body keeps the fast derive() from the
                // stored upstream hash (~1000x faster than SHA-256 of the body).
                final String etag = filtered
                    ? this.filteredEtag(rawBytes, tarballPrefix)
                    : this.rawFullEtag(metadata, rawBytes, tarballPrefix);
                if (clientETag.isPresent() && clientETag.get().equals(etag)) {
                    return ResponseBuilder.from(RsStatus.NOT_MODIFIED)
                        .header("ETag", etag)
                        .header("Cache-Control", "public, max-age=300")
                        .build();
                }
                final ByteLevelUrlTransformer transformer = new ByteLevelUrlTransformer();
                final byte[] transformedBytes = transformer.transform(rawBytes, tarballPrefix);
                final Content streamedContent = new Content.From(
                    Flowable.fromArray(ByteBuffer.wrap(transformedBytes))
                );
                return ResponseBuilder.ok()
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Last-Modified", metadata.lastModified())
                    .header("ETag", etag)
                    .header("Cache-Control", "public, max-age=300")
                    .header("CDN-Cache-Control", "public, max-age=600")
                    .body(streamedContent)
                    .build();
            }
            // Abbreviated requests should use serveAbbreviated() path, but handle fallback
            final ByteLevelUrlTransformer transformer = new ByteLevelUrlTransformer();
            final byte[] transformedBytes = transformer.transform(rawBytes, tarballPrefix);
            final String clientContent = new String(transformedBytes, StandardCharsets.UTF_8);
            final JsonObject fullJson = Json.createReader(new StringReader(clientContent)).readObject();
            final JsonObject enhanced = new MetadataEnhancer(fullJson).enhance();
            final JsonObject response = new AbbreviatedMetadata(enhanced).generate();
            final String responseStr = response.toString();
            final String etag = new MetadataETag(responseStr).calculate();
            if (clientETag.isPresent() && clientETag.get().equals(etag)) {
                return ResponseBuilder.from(RsStatus.NOT_MODIFIED)
                    .header("ETag", etag)
                    .header("Cache-Control", "public, max-age=300")
                    .build();
            }
            final Content streamedContent = new Content.From(
                Flowable.fromArray(ByteBuffer.wrap(responseStr.getBytes(StandardCharsets.UTF_8)))
            );
            return ResponseBuilder.ok()
                .header("Content-Type", "application/vnd.npm.install-v1+json; charset=utf-8")
                .header("Last-Modified", metadata.lastModified())
                .header("ETag", etag)
                .header("Cache-Control", "public, max-age=300")
                .header("CDN-Cache-Control", "public, max-age=600")
                .body(streamedContent)
                .build();
        } catch (final Exception e) {
            // Fallback to original implementation if streaming fails
            return this.buildResponseFallback(rawBytes, metadata, headers, abbreviated, clientETag);
        }
    }
    
    /**
     * Fallback response builder using DOM parsing (for error cases).
     */
    private Response buildResponseFallback(
        final byte[] rawBytes,
        final com.auto1.pantera.npm.proxy.model.NpmPackage.Metadata metadata,
        final Headers headers,
        final boolean abbreviated,
        final Optional<String> clientETag
    ) {
        final String rawContent = new String(rawBytes, StandardCharsets.UTF_8);
        final String clientContent = this.clientFormat(rawContent, headers);
        final JsonObject fullJson = Json.createReader(new StringReader(clientContent)).readObject();
        final JsonObject enhanced = new MetadataEnhancer(fullJson).enhance();
        final JsonObject response = abbreviated
            ? new AbbreviatedMetadata(enhanced).generate()
            : enhanced;
        final String responseStr = response.toString();
        final String etag = new MetadataETag(responseStr).calculate();

        if (clientETag.isPresent() && clientETag.get().equals(etag)) {
            return ResponseBuilder.from(RsStatus.NOT_MODIFIED)
                .header("ETag", etag)
                .header("Cache-Control", "public, max-age=300")
                .build();
        }

        final Content streamedContent = new Content.From(
            Flowable.fromArray(ByteBuffer.wrap(responseStr.getBytes(StandardCharsets.UTF_8)))
        );

        return ResponseBuilder.ok()
            .header("Content-Type", abbreviated
                ? "application/vnd.npm.install-v1+json; charset=utf-8"
                : "application/json; charset=utf-8")
            .header("Last-Modified", metadata.lastModified())
            .header("ETag", etag)
            .header("Cache-Control", "public, max-age=300")
            .header("CDN-Cache-Control", "public, max-age=600")
            .body(streamedContent)
            .build();
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
     * ETag for a cooldown-FILTERED body. Keyed to the bytes we actually serve
     * so it changes the instant a version is blocked or unblocked — which is
     * exactly what a raw-upstream-hash ETag does NOT do, because filtering
     * removes versions at serve time without touching the stored upstream
     * bytes. Without this, a client revalidating with {@code If-None-Match}
     * gets a stale {@code 304} and never sees a version that just aged out of
     * (or was released from) cooldown until it clears its own cache. Mirrors
     * the Maven adapter's served-bytes ETag. Hashes the pre-transform filtered
     * bytes; the tarball prefix is folded in by {@link MetadataETag#derive} so
     * the {@code 304} comparison still runs before URL transformation.
     *
     * @param servedBytes Pre-transform filtered metadata bytes
     * @param tarballPrefix Tarball URL prefix that the transform will apply
     * @return ETag derived from the filtered content
     */
    private String filteredEtag(final byte[] servedBytes, final String tarballPrefix) {
        return MetadataETag.derive(new MetadataETag(servedBytes).calculate(), tarballPrefix);
    }

    /**
     * ETag for a RAW full-metadata body — the fast path. Derives from the
     * immutable upstream content hash (~100 bytes hashed) when present, else
     * falls back to hashing the transformed bytes. Correct only for bytes that
     * are byte-for-byte the upstream packument (no cooldown, or a filter-error
     * fallback that serves unfiltered bytes); use {@link #filteredEtag} for any
     * body that went through the cooldown filter.
     *
     * @param metadata Package metadata carrying the stored content hash
     * @param rawBytes Raw metadata bytes (fallback hash source)
     * @param tarballPrefix Tarball URL prefix
     * @return ETag derived from the stored upstream hash
     */
    private String rawFullEtag(
        final com.auto1.pantera.npm.proxy.model.NpmPackage.Metadata metadata,
        final byte[] rawBytes,
        final String tarballPrefix
    ) {
        return metadata.contentHash()
            .map(hash -> MetadataETag.derive(hash, tarballPrefix))
            .orElseGet(() -> {
                final ByteLevelUrlTransformer transformer = new ByteLevelUrlTransformer();
                return new MetadataETag(transformer.transform(rawBytes, tarballPrefix)).calculate();
            });
    }

    /**
     * ETag for a RAW abbreviated-metadata body — the fast path, keyed to the
     * stored abbreviated content hash. See {@link #rawFullEtag}; use
     * {@link #filteredEtag} for filtered bodies.
     *
     * @param metadata Package metadata carrying the stored abbreviated hash
     * @param abbreviatedBytes Raw abbreviated bytes (fallback hash source)
     * @param tarballPrefix Tarball URL prefix
     * @return ETag derived from the stored abbreviated hash
     */
    private String rawAbbreviatedEtag(
        final com.auto1.pantera.npm.proxy.model.NpmPackage.Metadata metadata,
        final byte[] abbreviatedBytes,
        final String tarballPrefix
    ) {
        return metadata.abbreviatedHash()
            .map(hash -> MetadataETag.derive(hash, tarballPrefix))
            .orElseGet(() -> {
                final ByteLevelUrlTransformer transformer = new ByteLevelUrlTransformer();
                final byte[] transformed = transformer.transform(abbreviatedBytes, tarballPrefix);
                return new MetadataETag(transformed).calculate();
            });
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
     * Transform internal package format for external clients.
     * @param data Internal package data
     * @param headers Request headers
     * @return External client package
     */
    private String clientFormat(final String data, final Headers headers) {
        return new ClientContent(data, this.getTarballPrefix(headers)).value().toString();
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
     * reverse-proxy forwarding headers only when {@code PANTERA_TRUST_FORWARDED_HEADERS=true};
     * otherwise the origin is derived from {@code Host} alone.
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
