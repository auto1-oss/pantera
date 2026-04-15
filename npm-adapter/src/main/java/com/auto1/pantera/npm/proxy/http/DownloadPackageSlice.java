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
import com.auto1.pantera.npm.cooldown.NpmCooldownInspector;
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
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored -> {
            // P0.1: Check if client requests abbreviated format
            final boolean abbreviated = this.isAbbreviatedRequest(headers);

            // P0.2: Check for conditional request (If-None-Match)
            final Optional<String> clientETag = this.extractClientETag(headers);

            // URL-decode package name to handle scoped packages like @authn8%2fmcp-server -> @authn8/mcp-server
            final String rawPath = this.path.value(line.uri().getPath());
            final String packageName = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);

            // MEMORY OPTIMIZATION: Use different paths for abbreviated vs full requests
            if (abbreviated) {
                // FAST PATH: Serve pre-computed abbreviated metadata directly
                // This avoids loading/parsing full metadata (38MB → 3MB, no JSON parsing)
                return this.serveAbbreviated(packageName, headers, clientETag);
            } else {
                // FULL PATH: Load and process full metadata
                return this.serveFull(packageName, headers, clientETag);
            }
        }).exceptionally(error -> {
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
                .log();
            
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
        final Optional<String> clientETag
    ) {
        return this.npm.getPackageMetadataOnly(packageName)
            .flatMap(metadata -> {
                // PERF: Early 304 exit - skip content loading if derived ETag matches
                if (clientETag.isPresent() && metadata.abbreviatedHash().isPresent()) {
                    final String tarballPrefix = this.getTarballPrefix(headers);
                    final String derivedEtag = MetadataETag.derive(
                        metadata.abbreviatedHash().get(), tarballPrefix
                    );
                    if (clientETag.get().equals(derivedEtag)) {
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
                                        abbreviatedBytes, packageName, metadata, headers, clientETag
                                    );
                                }
                                // No cooldown - serve directly
                                return io.reactivex.Maybe.just(
                                    this.buildAbbreviatedResponse(abbreviatedBytes, metadata, headers, clientETag)
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
                                            rawBytes, packageName, metadata, headers, clientETag
                                        );
                                    }
                                    return io.reactivex.Maybe.just(
                                        this.buildResponse(rawBytes, metadata, headers, true, clientETag)
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
        final Optional<String> clientETag
    ) {
        // filterMetadata() parses JSON once and extracts release dates via ReleaseDateProvider
        // No need to pre-parse - that would double the parsing overhead
        final CompletableFuture<Response> filterFuture = this.applyFilterAndBuildResponse(
            abbreviatedBytes, packageName, metadata, headers, clientETag
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
        final Optional<String> clientETag
    ) {
        // Create inspector for cooldown evaluation - dates are preloaded from metadata
        final NpmCooldownInspector inspector = new NpmCooldownInspector();
        final CompletableFuture<Response> filterFuture = this.cooldownMetadata.filterMetadata(
            this.repoType,
            this.repoName,
            packageName,
            fullBytes,
            new NpmMetadataParser(),
            new NpmMetadataFilter(),
            new NpmMetadataRewriter(),
            Optional.of(inspector)
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
                    .log();
                return this.buildResponse(fullBytes, metadata, headers, true, clientETag);
            }
            return this.buildResponse(filtered, metadata, headers, true, clientETag);
        });
        return RxFuture.maybe(filterFuture);
    }

    /**
     * Apply cooldown filtering and build abbreviated response.
     * CooldownMetadataService handles JSON parsing and release date extraction internally.
     * NpmCooldownInspector is required for cooldown evaluation - release dates are preloaded
     * from metadata via ReleaseDateProvider, so no remote fetch is needed.
     */
    private CompletableFuture<Response> applyFilterAndBuildResponse(
        final byte[] abbreviatedBytes,
        final String packageName,
        final com.auto1.pantera.npm.proxy.model.NpmPackage.Metadata metadata,
        final Headers headers,
        final Optional<String> clientETag
    ) {
        // Create inspector for cooldown evaluation - dates are preloaded from metadata
        final NpmCooldownInspector inspector = new NpmCooldownInspector();
        return this.cooldownMetadata.filterMetadata(
            this.repoType,
            this.repoName,
            packageName,
            abbreviatedBytes,
            new NpmMetadataParser(),
            new NpmMetadataFilter(),
            new NpmMetadataRewriter(),
            Optional.of(inspector)
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
                        .log();
                    return this.buildAbbreviatedResponse(abbreviatedBytes, metadata, headers, clientETag);
                }
                // Success - build response with filtered abbreviated metadata
                return this.buildAbbreviatedResponse(filtered, metadata, headers, clientETag);
            });
    }

    /**
     * Serve full metadata with cooldown filtering support.
     */
    private CompletableFuture<Response> serveFull(
        final String packageName,
        final Headers headers,
        final Optional<String> clientETag
    ) {
        return this.npm.getPackageMetadataOnly(packageName)
            .flatMap(metadata -> {
                // PERF: Early 304 exit - skip content loading if derived ETag matches
                if (clientETag.isPresent() && metadata.contentHash().isPresent()) {
                    final String tarballPrefix = this.getTarballPrefix(headers);
                    final String derivedEtag = MetadataETag.derive(
                        metadata.contentHash().get(), tarballPrefix
                    );
                    if (clientETag.get().equals(derivedEtag)) {
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
                            // Create inspector for cooldown evaluation - dates are preloaded from metadata
                            if (this.cooldownMetadata != null && this.repoType != null) {
                                final NpmCooldownInspector inspector = new NpmCooldownInspector();
                                final CompletableFuture<Response> filterFuture = 
                                    this.cooldownMetadata.filterMetadata(
                                        this.repoType,
                                        this.repoName,
                                        packageName,
                                        rawBytes,
                                        new NpmMetadataParser(),
                                        new NpmMetadataFilter(),
                                        new NpmMetadataRewriter(),
                                        Optional.of(inspector)
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
                                                .log();
                                            return this.buildResponse(rawBytes, metadata, headers, false, clientETag);
                                        }
                                        return this.buildResponse(filtered, metadata, headers, false, clientETag);
                                    });
                                return RxFuture.maybe(filterFuture);
                            }
                            return io.reactivex.Maybe.just(
                                this.buildResponse(rawBytes, metadata, headers, false, clientETag)
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
        final Optional<String> clientETag
    ) {
        final String tarballPrefix = this.getTarballPrefix(headers);
        // PERF: Derive ETag from pre-computed hash + prefix (~100 bytes to hash)
        // instead of SHA-256 of full transformed content (3-5MB). ~1000x faster.
        final String etag = metadata.abbreviatedHash()
            .map(hash -> MetadataETag.derive(hash, tarballPrefix))
            .orElseGet(() -> {
                final ByteLevelUrlTransformer transformer = new ByteLevelUrlTransformer();
                final byte[] transformed = transformer.transform(abbreviatedBytes, tarballPrefix);
                return new MetadataETag(transformed).calculate();
            });
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
        final Optional<String> clientETag
    ) {
        try {
            final String tarballPrefix = this.getTarballPrefix(headers);
            // For full metadata requests (abbreviated=false), we can skip JSON parsing
            if (!abbreviated) {
                // PERF: Derive ETag from pre-computed hash + prefix (~100 bytes)
                // instead of SHA-256 of full transformed content (3-5MB). ~1000x faster.
                final String etag = metadata.contentHash()
                    .map(hash -> MetadataETag.derive(hash, tarballPrefix))
                    .orElseGet(() -> {
                        final ByteLevelUrlTransformer t = new ByteLevelUrlTransformer();
                        return new MetadataETag(t.transform(rawBytes, tarballPrefix)).calculate();
                    });
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
     * Get tarball URL prefix for streaming transformer.
     */
    private String getTarballPrefix(final Headers headers) {
        if (this.baseUrl.isPresent()) {
            return this.baseUrl.get().toString();
        }
        final String host = StreamSupport.stream(headers.spliterator(), false)
            .filter(e -> "Host".equalsIgnoreCase(e.getKey()))
            .findAny()
            .map(Header::getValue)
            .orElse("localhost");
        return this.assetPrefix(host);
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
    private String clientFormat(final String data,
        final Iterable<Header> headers) {
        final String prefix;
        if (this.baseUrl.isPresent()) {
            // Use configured repository URL
            prefix = this.baseUrl.get().toString();
        } else {
            // Fall back to Host header
            final String host = StreamSupport.stream(headers.spliterator(), false)
                .filter(e -> "Host".equalsIgnoreCase(e.getKey()))
                .findAny().orElseThrow(
                    () -> new RuntimeException("Could not find Host header in request")
                ).getValue();
            prefix = this.assetPrefix(host);
        }
        return new ClientContent(data, prefix).value().toString();
    }

    /**
     * Generates asset base reference.
     * @param host External host
     * @return Asset base reference
     */
    private String assetPrefix(final String host) {
        final String result;
        if (StringUtils.isEmpty(this.path.prefix())) {
            result = String.format("http://%s", host);
        } else {
            result = String.format("http://%s/%s", host, this.path.prefix());
        }
        return result;
    }
}
