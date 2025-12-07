/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.proxy.http;

import com.artipie.asto.Concatenation;
import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RequestLine;
import com.artipie.npm.proxy.NpmProxy;
import com.artipie.npm.proxy.json.ClientContent;
import com.artipie.npm.misc.AbbreviatedMetadata;
import com.artipie.npm.misc.MetadataETag;
import com.artipie.npm.misc.MetadataEnhancer;
import com.artipie.cooldown.metadata.CooldownMetadataService;
import com.artipie.cooldown.metadata.AllVersionsBlockedException;
import com.artipie.http.log.EcsLogger;
import com.artipie.npm.cooldown.NpmMetadataParser;
import com.artipie.npm.cooldown.NpmMetadataFilter;
import com.artipie.npm.cooldown.NpmMetadataRewriter;
import com.artipie.npm.cooldown.NpmCooldownInspector;
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

            // CRITICAL MEMORY FIX: Fully reactive processing - NO blocking!
            // This allows GC to clean up intermediate objects immediately after use
            return this.npm.getPackageMetadataOnly(packageName)
                .flatMap(metadata ->
                    this.npm.getPackageContentStream(packageName).flatMap(contentStream ->
                        // Process content stream reactively
                        new Concatenation(contentStream)
                            .single()
                            .map(ByteBuffer::array)
                            .toMaybe()
                            .flatMap(rawBytes -> {
                                // Apply cooldown filtering if available
                                if (this.cooldownMetadata != null && this.repoType != null) {
                                    // Create a fresh MetadataAwareInspector for this request
                                    // This inspector will have dates preloaded from the metadata
                                    final NpmCooldownInspector inspector = new NpmCooldownInspector();
                                    // Handle cooldown filtering - use handle() instead of exceptionally()
                                    // because Maybe.fromFuture(null) becomes empty and skips flatMap
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
                                                // Check for AllVersionsBlockedException
                                                Throwable cause = ex;
                                                while (cause != null) {
                                                    if (cause instanceof AllVersionsBlockedException) {
                                                        EcsLogger.info("com.artipie.npm")
                                                            .message("All versions blocked by cooldown")
                                                            .eventCategory("cooldown")
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
                                                // Other error - fall back to unfiltered
                                                EcsLogger.warn("com.artipie.npm")
                                                    .message("Cooldown filter error - falling back to unfiltered")
                                                    .eventCategory("cooldown")
                                                    .eventAction("filter_error")
                                                    .field("package.name", packageName)
                                                    .error(ex)
                                                    .log();
                                                return this.buildResponse(rawBytes, metadata, headers, abbreviated, clientETag);
                                            }
                                            // Success - build response with filtered metadata
                                            return this.buildResponse(filtered, metadata, headers, abbreviated, clientETag);
                                        });
                                    return io.reactivex.Maybe.fromFuture(filterFuture);
                                }
                                // No cooldown filtering - process normally
                                return io.reactivex.Maybe.just(
                                    this.buildResponse(rawBytes, metadata, headers, abbreviated, clientETag)
                                );
                            })
                    )
                )
                .toSingle(ResponseBuilder.notFound().build())
                .to(SingleInterop.get())
                .toCompletableFuture();
        });
    }

    /**
     * Build HTTP response from metadata bytes.
     */
    private Response buildResponse(
        final byte[] rawBytes,
        final com.artipie.npm.proxy.model.NpmPackage.Metadata metadata,
        final Headers headers,
        final boolean abbreviated,
        final Optional<String> clientETag
    ) {
        final String rawContent = new String(rawBytes, StandardCharsets.UTF_8);
        // Get client-formatted content (with rewritten tarball URLs)
        final String clientContent = this.clientFormat(rawContent, headers);

        // Parse JSON for processing
        final JsonObject fullJson = Json.createReader(
            new StringReader(clientContent)
        ).readObject();

        // P1.1: Enhance metadata with time and users objects
        final JsonObject enhanced = new MetadataEnhancer(fullJson).enhance();

        // P0.1: Generate abbreviated or full format
        final JsonObject response = abbreviated
            ? new AbbreviatedMetadata(enhanced).generate()
            : enhanced;

        final String responseStr = response.toString();

        // P0.2: Calculate ETag
        final String etag = new MetadataETag(responseStr).calculate();

        // P0.2: Check if client has matching ETag (304 Not Modified)
        if (clientETag.isPresent() && clientETag.get().equals(etag)) {
            return ResponseBuilder.from(RsStatus.NOT_MODIFIED)
                .header("ETag", etag)
                .header("Cache-Control", "public, max-age=300")
                .build();
        }

        // CRITICAL MEMORY FIX: Use reactive Content that streams the response
        // The byte array will be GC'd after streaming completes
        final Content streamedContent = new Content.From(
            Flowable.fromArray(
                ByteBuffer.wrap(responseStr.getBytes(StandardCharsets.UTF_8))
            )
        );

        // Return streaming response
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
