/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jcabi.log.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
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
 *   <li>Caches merged result (5 minute TTL)</li>
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
     * Metadata cache for merged results.
     * Short TTL because metadata changes frequently during deployments.
     */
    private static final Cache<String, byte[]> METADATA_CACHE = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .build();

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

        // All other requests use standard group behavior
        return delegate.response(line, headers, body);
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
        final String cacheKey = this.group + ":" + path;
        
        // Check cache first
        final byte[] cached = METADATA_CACHE.getIfPresent(cacheKey);
        if (cached != null) {
            Logger.debug(
                this,
                "Maven group %s: returning cached merged metadata for %s",
                this.group,
                path
            );
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", "application/xml")
                    .body(cached)
                    .build()
            );
        }

        // CRITICAL: Consume original body to prevent OneTimePublisher errors
        // GET requests for maven-metadata.xml have empty bodies, but Content is still reference-counted
        return body.asBytesFuture().thenCompose(requestBytes -> {
            // Track merge duration for slow request logging
            final long mergeStartTime = System.currentTimeMillis();

            // Fetch metadata from all members in parallel with Content.EMPTY
            final List<CompletableFuture<byte[]>> futures = new ArrayList<>();
            
            for (String member : this.members) {
            final Slice memberSlice = this.resolver.slice(
                new Key.From(member),
                this.port,
                this.depth + 1
            );
            
            // Rewrite request to include member name in path
            final RequestLine rewritten = rewritePath(line, member);
            
            final CompletableFuture<byte[]> memberFuture = memberSlice
                .response(rewritten, dropFullPathHeader(headers), Content.EMPTY)
                .thenCompose(resp -> {
                    if (resp.status() == RsStatus.OK) {
                        return readResponseBody(resp.body());
                    } else {
                        // CRITICAL: Consume body before returning null to prevent memory leak
                        // Member doesn't have this metadata - consume body and return null
                        return resp.body().asBytesFuture().thenApply(ignored -> null);
                    }
                })
                .exceptionally(err -> {
                    final String errMsg = err.getMessage() != null ? err.getMessage() : err.getClass().getName();
                    final String causeMsg = err.getCause() != null ? err.getCause().toString() : "no cause";
                    Logger.warn(
                        this,
                        "Maven group %s: member %s failed to fetch metadata: %s (cause: %s)",
                        this.group,
                        member,
                        errMsg,
                        causeMsg
                    );
                    return null;
                });
            
            futures.add(memberFuture);
        }

        // Wait for all members and merge results
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenCompose(v -> {
                final List<byte[]> metadataList = new ArrayList<>();
                for (CompletableFuture<byte[]> future : futures) {
                    final byte[] metadata = future.join();
                    if (metadata != null && metadata.length > 0) {
                        metadataList.add(metadata);
                    }
                }

                if (metadataList.isEmpty()) {
                    Logger.warn(
                        this,
                        "Maven group %s: no metadata found in any member for %s",
                        this.group,
                        path
                    );
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.notFound().build()
                    );
                }

                // Use reflection to call MetadataMerger from maven-adapter module
                // This avoids circular dependency issues
                return mergeUsingReflection(metadataList)
                    .thenApply(mergedBytes -> {
                        final long mergeDuration = System.currentTimeMillis() - mergeStartTime;
                        
                        // Cache the merged result
                        METADATA_CACHE.put(cacheKey, mergedBytes);
                        
                        // Only log slow merges
                        if (mergeDuration > 500) {
                            Logger.warn(
                                this,
                                "Maven group %s: slow metadata merge (%dms) from %d members for %s",
                                this.group,
                                mergeDuration,
                                metadataList.size(),
                                path
                            );
                        }
                        
                        return ResponseBuilder.ok()
                            .header("Content-Type", "application/xml")
                            .body(mergedBytes)
                            .build();
                    });
            })
            .exceptionally(err -> {
                Logger.error(
                    this,
                    "Maven group %s: failed to merge metadata for %s: %s",
                    this.group,
                    path,
                    err.getMessage()
                );
                return ResponseBuilder.internalError()
                    .textBody("Failed to merge metadata: " + err.getMessage())
                    .build();
            });
        }); // Close thenCompose lambda for body consumption
    }

    /**
     * Merge metadata using MetadataMerger from maven-adapter via reflection.
     * This allows artipie-main to call maven-adapter without circular dependency.
     */
    private CompletableFuture<byte[]> mergeUsingReflection(final List<byte[]> metadataList) {
        try {
            // Load MetadataMerger class
            final Class<?> mergerClass = Class.forName(
                "com.artipie.maven.metadata.MetadataMerger"
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
            Logger.error(
                this,
                "Failed to merge metadata using reflection: %s",
                e.getMessage()
            );
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
     * Rewrite request path to include member repository name.
     */
    private static RequestLine rewritePath(final RequestLine original, final String member) {
        final String path = original.uri().getPath();
        final String newPath = "/" + member + (path.startsWith("/") ? path : "/" + path);
        return new RequestLine(
            original.method().value(),
            newPath,
            original.version()
        );
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
}
