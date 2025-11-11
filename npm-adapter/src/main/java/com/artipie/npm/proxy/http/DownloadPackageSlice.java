/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.proxy.http;

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
import hu.akarnokd.rxjava2.interop.SingleInterop;
import org.apache.commons.lang3.StringUtils;
import javax.json.Json;
import javax.json.JsonObject;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import java.net.URL;
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
     * @param npm NPM Proxy facade
     * @param path Package path helper
     */
    public DownloadPackageSlice(final NpmProxy npm, final PackagePath path) {
        this(npm, path, Optional.empty());
    }

    /**
     * @param npm NPM Proxy facade
     * @param path Package path helper
     * @param baseUrl Base URL for the repository
     */
    public DownloadPackageSlice(final NpmProxy npm, final PackagePath path, final Optional<URL> baseUrl) {
        this.npm = npm;
        this.path = path;
        this.baseUrl = baseUrl;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        // P0.1: Check if client requests abbreviated format
        final boolean abbreviated = this.isAbbreviatedRequest(headers);
        
        // P0.2: Check for conditional request (If-None-Match)
        final Optional<String> clientETag = this.extractClientETag(headers);
        
        return this.npm.getPackage(this.path.value(line.uri().getPath()))
            .map(pkg -> {
                // Get client-formatted content (with rewritten tarball URLs)
                final String clientContent = this.clientFormat(pkg.content(), headers);
                
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
                
                // Return full response with ETag and cache headers
                return ResponseBuilder.ok()
                    .header("Content-Type", abbreviated
                        ? "application/vnd.npm.install-v1+json; charset=utf-8"
                        : "application/json; charset=utf-8")
                    .header("Last-Modified", pkg.meta().lastModified())
                    .header("ETag", etag)
                    .header("Cache-Control", "public, max-age=300")
                    .header("CDN-Cache-Control", "public, max-age=600")
                    .body(responseStr.getBytes(StandardCharsets.UTF_8))
                    .build();
            }).toSingle(ResponseBuilder.notFound().build())
            .to(SingleInterop.get())
            .toCompletableFuture();
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
