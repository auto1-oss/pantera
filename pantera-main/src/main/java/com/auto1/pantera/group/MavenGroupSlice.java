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
package com.auto1.pantera.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.log.EcsLogger;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Maven-specific group slice with metadata merging support.
 * Extends basic GroupSlice behavior with Maven metadata aggregation.
 *
 * <p>For maven-metadata.xml requests:
 * <ul>
 *   <li>Fetches metadata from ALL members (not just first)</li>
 *   <li>Merges all metadata files using external MetadataMerger</li>
 *   <li>Caches merged result (12 hour TTL, L1+L2)</li>
 *   <li>Returns merged metadata to client</li>
 * </ul>
 *
 * <p>For all other requests:
 * <ul>
 *   <li>Returns first successful response (standard group behavior)</li>
 * </ul>
 *
 * @since 1.0
 */
public final class MavenGroupSlice implements Slice {

    /**
     * Delegate group slice for non-metadata requests.
     */
    private final Slice delegate;

    /**
     * Member repository names.
     */
    private final List<String> members;

    /**
     * Slice resolver for getting member slices.
     */
    private final SliceResolver resolver;

    /**
     * Server port.
     */
    private final int port;

    /**
     * Group repository name.
     */
    private final String group;

    /**
     * Nesting depth.
     */
    private final int depth;

    /**
     * Two-tier metadata cache (L1: Caffeine, L2: Valkey).
     */
    private final GroupMetadataCache metadataCache;

    /**
     * Constructor.
     * @param delegate Delegate group slice
     * @param group Group repository name
     * @param members Member repository names
     * @param resolver Slice resolver
     * @param port Server port
     * @param depth Nesting depth
     */
    public MavenGroupSlice(
        final Slice delegate,
        final String group,
        final List<String> members,
        final SliceResolver resolver,
        final int port,
        final int depth
    ) {
        this.delegate = delegate;
        this.group = group;
        this.members = members;
        this.resolver = resolver;
        this.port = port;
        this.depth = depth;
        this.metadataCache = new GroupMetadataCache(group);
    }

    /**
     * Constructor with injectable cache (for testing).
     * @param delegate Delegate group slice
     * @param group Group repository name
     * @param members Member repository names
     * @param resolver Slice resolver
     * @param port Server port
     * @param depth Nesting depth
     * @param cache Group metadata cache to use
     */
    public MavenGroupSlice(
        final Slice delegate,
        final String group,
        final List<String> members,
        final SliceResolver resolver,
        final int port,
        final int depth,
        final GroupMetadataCache cache
    ) {
        this.delegate = delegate;
        this.group = group;
        this.members = members;
        this.resolver = resolver;
        this.port = port;
        this.depth = depth;
        this.metadataCache = cache;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().getPath();
        final String method = line.method().value();

        // Only merge metadata for GET requests
        if ("GET".equals(method) && path.endsWith("maven-metadata.xml")) {
            return mergeMetadata(line, headers, body, path);
        }

        // Handle checksum requests for merged metadata
        if ("GET".equals(method) && (path.endsWith("maven-metadata.xml.sha1") || path.endsWith("maven-metadata.xml.md5"))) {
            return handleChecksumRequest(line, headers, body, path);
        }

        // All other requests use standard group behavior
        return delegate.response(line, headers, body);
    }

    /**
     * Handle checksum requests for merged metadata.
     * Computes checksum of the merged metadata and returns it.
     */
    private CompletableFuture<Response> handleChecksumRequest(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final String path
    ) {
        // Determine checksum type
        final boolean isSha1 = path.endsWith(".sha1");
        final String metadataPath = path.substring(0, path.lastIndexOf('.'));

        // Get merged metadata from cache or merge it
        final RequestLine metadataLine = new RequestLine(
            line.method(),
            URI.create(metadataPath),
            line.version()
        );

        return mergeMetadata(metadataLine, headers, body, metadataPath)
            .thenApply(metadataResponse -> {
                // Extract body from metadata response
                return metadataResponse.body().asBytesFuture()
                    .thenApply(metadataBytes -> {
                        try {
                            // Compute checksum
                            final java.security.MessageDigest digest = java.security.MessageDigest.getInstance(
                                isSha1 ? "SHA-1" : "MD5"
                            );
                            final byte[] checksumBytes = digest.digest(metadataBytes);

                            // Convert to hex string
                            final StringBuilder hexString = new StringBuilder();
                            for (byte b : checksumBytes) {
                                hexString.append(String.format("%02x", b));
                            }

                            return ResponseBuilder.ok()
                                .header("Content-Type", "text/plain")
                                .body(hexString.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                .build();
                        } catch (java.security.NoSuchAlgorithmException e) {
                            EcsLogger.error("com.auto1.pantera.maven")
                                .message("Failed to compute checksum")
                                .eventCategory("repository")
                                .eventAction("checksum_compute")
                                .eventOutcome("failure")
                                .field("url.path", path)
                                .error(e)
                                .log();
                            return ResponseBuilder.internalError()
                                .textBody("Failed to compute checksum")
                                .build();
                        }
                    });
            })
            .thenCompose(future -> future);
    }

    /**
     * Merge maven-metadata.xml from all members.
     */
    private CompletableFuture<Response> mergeMetadata(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final String path
    ) {
        final String cacheKey = path;

        // Check two-tier cache (L1 then L2 if miss)
        return this.metadataCache.get(cacheKey).thenCompose(cached -> {
            if (cached.isPresent()) {
                // Cache HIT (L1 or L2)
                EcsLogger.debug("com.auto1.pantera.maven")
                    .message("Returning cached merged metadata (cache hit)")
                    .eventCategory("repository")
                    .eventAction("metadata_merge")
                    .eventOutcome("success")
                    .field("repository.name", this.group)
                    .field("url.path", path)
                    .log();
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header("Content-Type", "application/xml")
                        .body(cached.get())
                        .build()
                );
            }

            // Cache MISS - fetch and merge from members
            // CRITICAL: Consume original body to prevent OneTimePublisher errors
            // GET requests for maven-metadata.xml have empty bodies, but Content is still reference-counted
            return CompletableFuture.completedFuture((byte[]) null).thenCompose(requestBytes -> {
            // Track fetch duration separately from merge duration
            final long fetchStartTime = System.currentTimeMillis();

            // Fetch metadata from all members in parallel with Content.EMPTY
            final List<CompletableFuture<byte[]>> futures = new ArrayList<>();
            
            for (String member : this.members) {
            final Slice memberSlice = this.resolver.slice(
                new Key.From(member),
                this.port,
                this.depth + 1
            );

            // CRITICAL: Member slices are wrapped in TrimPathSlice which expects paths with member prefix
            // Example: /member/org/apache/maven/plugins/maven-metadata.xml
            // We must add the member prefix to the path before calling the member slice
            final RequestLine memberLine = rewritePath(line, member);

            final CompletableFuture<byte[]> memberFuture = memberSlice
                .response(memberLine, dropFullPathHeader(headers), Content.EMPTY)
                .thenCompose(resp -> {
                    if (resp.status() == RsStatus.OK) {
                        return readResponseBody(resp.body());
                    } else {
                        // Drain non-OK response body to release upstream connection
                        return resp.body().asBytesFuture()
                            .thenApply(ignored -> (byte[]) null);
                    }
                })
                .exceptionally(err -> {
                    EcsLogger.warn("com.auto1.pantera.maven")
                        .message("Member failed to fetch metadata: " + member)
                        .eventCategory("repository")
                        .eventAction("metadata_fetch")
                        .eventOutcome("failure")
                        .field("repository.name", this.group)
                        .error(err)
                        .log();
                    return null;
                });
            
            futures.add(memberFuture);
        }

        // Wait for all members and merge results
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenCompose(v -> {
                final List<byte[]> metadataList = new ArrayList<>();
                for (CompletableFuture<byte[]> future : futures) {
                    final byte[] metadata = future.getNow(null);
                    if (metadata != null && metadata.length > 0) {
                        metadataList.add(metadata);
                    }
                }

                // Calculate fetch duration (time to get data from all members)
                final long fetchDuration = System.currentTimeMillis() - fetchStartTime;

                if (metadataList.isEmpty()) {
                    // All members failed — try last-known-good stale fallback
                    return MavenGroupSlice.this.metadataCache.getStale(cacheKey)
                        .thenApply(stale -> {
                            if (stale.isPresent()) {
                                EcsLogger.warn("com.auto1.pantera.maven")
                                    .message("Returning stale metadata (all members failed)")
                                    .eventCategory("repository")
                                    .eventAction("metadata_merge")
                                    .eventOutcome("stale_fallback")
                                    .field("repository.name", MavenGroupSlice.this.group)
                                    .field("url.path", path)
                                    .field("event.duration", fetchDuration * 1_000_000L)
                                    .log();
                                return ResponseBuilder.ok()
                                    .header("Content-Type", "application/xml")
                                    .body(stale.get())
                                    .build();
                            }
                            EcsLogger.warn("com.auto1.pantera.maven")
                                .message("No metadata found in any member and no stale fallback")
                                .eventCategory("repository")
                                .eventAction("metadata_merge")
                                .eventOutcome("failure")
                                .field("repository.name", MavenGroupSlice.this.group)
                                .field("url.path", path)
                                .field("event.duration", fetchDuration * 1_000_000L)
                                .log();
                            return ResponseBuilder.notFound().build();
                        });
                }

                // Track merge duration separately (actual XML processing time)
                final long mergeStartTime = System.currentTimeMillis();

                // Use reflection to call MetadataMerger from maven-adapter module
                // This avoids circular dependency issues
                return mergeUsingReflection(metadataList)
                    .thenApply(mergedBytes -> {
                        final long mergeDuration = System.currentTimeMillis() - mergeStartTime;
                        final long totalDuration = fetchDuration + mergeDuration;

                        // Cache the merged result (L1 + L2)
                        MavenGroupSlice.this.metadataCache.put(cacheKey, mergedBytes);

                        // Record metadata merge metrics (total for backward compatibility)
                        recordMetadataOperation("merge", totalDuration);

                        // Log slow fetches (>500ms) - expected for proxy repos
                        if (fetchDuration > 500) {
                            EcsLogger.debug("com.auto1.pantera.maven")
                                .message(String.format("Slow member fetch (%d members), merge took %dms", metadataList.size(), mergeDuration))
                                .eventCategory("repository")
                                .eventAction("metadata_fetch")
                                .eventOutcome("success")
                                .field("repository.name", this.group)
                                .field("url.path", path)
                                .field("event.duration", fetchDuration * 1_000_000L)
                                .log();
                        }

                        // Log slow merges (>50ms) - indicates actual performance issue
                        if (mergeDuration > 50) {
                            EcsLogger.warn("com.auto1.pantera.maven")
                                .message(String.format("Slow metadata merge (%d members), fetch took %dms", metadataList.size(), fetchDuration))
                                .eventCategory("repository")
                                .eventAction("metadata_merge")
                                .eventOutcome("success")
                                .field("repository.name", this.group)
                                .field("url.path", path)
                                .field("event.duration", mergeDuration * 1_000_000L)
                                .log();
                        }

                        return ResponseBuilder.ok()
                            .header("Content-Type", "application/xml")
                            .body(mergedBytes)
                            .build();
                    });
            })
            .exceptionally(err -> {
                // Unwrap CompletionException to get the real cause
                final Throwable cause = err.getCause() != null ? err.getCause() : err;
                EcsLogger.error("com.auto1.pantera.maven")
                    .message("Failed to merge metadata")
                    .eventCategory("repository")
                    .eventAction("metadata_merge")
                    .eventOutcome("failure")
                    .field("repository.name", this.group)
                    .field("url.path", path)
                    .error(cause)
                    .log();
                return ResponseBuilder.internalError()
                    .textBody("Failed to merge metadata: " + cause.getMessage())
                    .build();
            });
            }); // Close thenCompose lambda for body consumption
        }); // Close metadataCache.get() thenCompose
    }

    /**
     * Merge metadata using MetadataMerger from maven-adapter via reflection.
     * This allows pantera-main to call maven-adapter without circular dependency.
     */
    private CompletableFuture<byte[]> mergeUsingReflection(final List<byte[]> metadataList) {
        try {
            // Load MetadataMerger class
            final Class<?> mergerClass = Class.forName(
                "com.auto1.pantera.maven.metadata.MetadataMerger"
            );
            
            // Create instance
            final Object merger = mergerClass
                .getConstructor(List.class)
                .newInstance(metadataList);
            
            // Call merge() method
            @SuppressWarnings("unchecked")
            final CompletableFuture<Content> mergeFuture = (CompletableFuture<Content>)
                mergerClass.getMethod("merge").invoke(merger);
            
            // Read content
            return mergeFuture.thenCompose(this::readResponseBody);
            
        } catch (Exception e) {
            EcsLogger.error("com.auto1.pantera.maven")
                .message("Failed to merge metadata using reflection")
                .eventCategory("repository")
                .eventAction("metadata_merge")
                .eventOutcome("failure")
                .error(e)
                .log();
            return CompletableFuture.failedFuture(
                new IllegalStateException("Maven metadata merging not available", e)
            );
        }
    }

    /**
     * Read entire response body into byte array.
     */
    private CompletableFuture<byte[]> readResponseBody(final Content content) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        
        content.subscribe(new org.reactivestreams.Subscriber<ByteBuffer>() {
            private org.reactivestreams.Subscription subscription;
            
            @Override
            public void onSubscribe(final org.reactivestreams.Subscription sub) {
                this.subscription = sub;
                sub.request(Long.MAX_VALUE);
            }
            
            @Override
            public void onNext(final ByteBuffer buffer) {
                final byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                try {
                    baos.write(bytes);
                } catch (Exception e) {
                    result.completeExceptionally(e);
                    this.subscription.cancel();
                }
            }
            
            @Override
            public void onError(final Throwable err) {
                result.completeExceptionally(err);
            }
            
            @Override
            public void onComplete() {
                result.complete(baos.toByteArray());
            }
        });
        
        return result;
    }

    /**
     * Drop X-FullPath header to avoid recursion detection issues.
     */
    private static Headers dropFullPathHeader(final Headers headers) {
        return new Headers(
            headers.asList().stream()
                .filter(h -> !h.getKey().equalsIgnoreCase("X-FullPath"))
                .toList()
        );
    }

    /**
     * Rewrite request path to include member repository name.
     *
     * <p>Member slices are wrapped in TrimPathSlice which expects paths with member prefix.
     * This method adds the member prefix to the path.
     *
     * <p>Example: /org/apache/maven/plugins/maven-metadata.xml → /member/org/apache/maven/plugins/maven-metadata.xml
     *
     * @param original Original request line
     * @param member Member repository name to prefix
     * @return Rewritten request line with member prefix
     */
    private static RequestLine rewritePath(final RequestLine original, final String member) {
        final URI uri = original.uri();
        final String raw = uri.getRawPath();
        final String base = raw.startsWith("/") ? raw : "/" + raw;
        final String prefix = "/" + member + "/";

        // Avoid double-prefixing
        final String path = base.startsWith(prefix) ? base : ("/" + member + base);

        final StringBuilder full = new StringBuilder(path);
        if (uri.getRawQuery() != null) {
            full.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            full.append('#').append(uri.getRawFragment());
        }

        try {
            return new RequestLine(
                original.method(),
                new URI(full.toString()),
                original.version()
            );
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Failed to rewrite path", ex);
        }
    }

    private void recordMetadataOperation(final String operation, final long duration) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordMetadataOperation(this.group, "maven", operation);
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordMetadataGenerationDuration(this.group, "maven", duration);
        }
    }

}
