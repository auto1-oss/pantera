/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.slice.ContentWithSize;
import org.apache.commons.codec.digest.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Slice that generates checksum files (.sha1, .md5, .sha256) for Maven artifacts.
 * This dramatically improves Maven client performance by providing checksums without
 * additional round-trips to upstream.
 * 
 * @since 0.1
 */
final class ChecksumProxySlice implements Slice {

    /**
     * Upstream slice.
     */
    private final Slice upstream;

    /**
     * Wraps upstream slice with checksum generation.
     * 
     * @param upstream Upstream slice
     */
    ChecksumProxySlice(final Slice upstream) {
        this.upstream = upstream;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final String path = line.uri().getPath();
        
        // Check if this is a checksum file request
        if (path.endsWith(".sha1")) {
            return this.generateChecksum(line, headers, path, "SHA-1", ".sha1");
        } else if (path.endsWith(".md5")) {
            return this.generateChecksum(line, headers, path, "MD5", ".md5");
        } else if (path.endsWith(".sha256")) {
            return this.generateChecksum(line, headers, path, "SHA-256", ".sha256");
        } else if (path.endsWith(".sha512")) {
            return this.generateChecksum(line, headers, path, "SHA-512", ".sha512");
        }
        
        // Not a checksum request - pass through
        return this.upstream.response(line, headers, body);
    }

    /**
     * Get checksum for an artifact with optimized strategy.
     * Priority: 1) Cached/upstream checksum file, 2) Compute from cached artifact (fallback only)
     * This avoids expensive hash computation when checksums are already available.
     * 
     * @param line Request line
     * @param headers Request headers
     * @param checksumPath Path to checksum file (e.g., "/repo/artifact-1.0.jar.sha1")
     * @param algorithm Hash algorithm (e.g., "SHA-1", "MD5")
     * @param extension Checksum file extension (e.g., ".sha1", ".md5")
     * @return Response future
     */
    private CompletableFuture<Response> generateChecksum(
        final RequestLine line,
        final Headers headers,
        final String checksumPath,
        final String algorithm,
        final String extension
    ) {
        // OPTIMIZATION: Try to fetch checksum directly from cache/upstream FIRST
        // This is much faster than computing from artifact bytes
        return this.upstream.response(line, headers, Content.EMPTY)
            .thenCompose(checksumResp -> {
                if (checksumResp.status().success()) {
                    // Checksum file found in cache or upstream - use it directly!
                    return CompletableFuture.completedFuture(checksumResp);
                }
                
                // Checksum not available - FALLBACK: compute from artifact
                // This is expensive but ensures we can always provide checksums
                final String artifactPath = checksumPath.substring(
                    0, checksumPath.length() - extension.length()
                );
                final RequestLine artifactLine = new RequestLine(
                    line.method().value(),
                    artifactPath,
                    line.version()
                );
                
                return this.upstream.response(artifactLine, headers, Content.EMPTY)
                    .thenCompose(artifactResp -> {
                        if (!artifactResp.status().success()) {
                            // Neither checksum nor artifact found
                            return CompletableFuture.completedFuture(
                                ResponseBuilder.notFound().build()
                            );
                        }
                        
                        // Artifact found - compute checksum as last resort
                        return new ContentWithSize(artifactResp.body(), artifactResp.headers())
                            .asBytesFuture()
                            .thenApply(bytes -> {
                                final String hash;
                                switch (algorithm) {
                                    case "SHA-1":
                                        hash = DigestUtils.sha1Hex(bytes);
                                        break;
                                    case "MD5":
                                        hash = DigestUtils.md5Hex(bytes);
                                        break;
                                    case "SHA-256":
                                        hash = DigestUtils.sha256Hex(bytes);
                                        break;
                                    case "SHA-512":
                                        hash = DigestUtils.sha512Hex(bytes);
                                        break;
                                    default:
                                        throw new IllegalArgumentException(
                                            "Unsupported algorithm: " + algorithm
                                        );
                                }
                                final byte[] checksumBytes = hash.getBytes(StandardCharsets.UTF_8);
                                return ResponseBuilder.ok()
                                    .header("Content-Type", "text/plain")
                                    .header("Content-Length", String.valueOf(checksumBytes.length))
                                    .body(checksumBytes)
                                    .build();
                            });
                    });
            });
    }
}
