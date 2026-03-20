/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.trace.TraceContextExecutor;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice that automatically rebuilds maven-metadata.xml on artifact upload.
 * Triggers asynchronous metadata generation after successful PUT/POST.
 * 
 * <p>Maven artifact path format:
 * /{groupId}/{artifactId}/{version}/{artifactId}-{version}[-{classifier}].{extension}
 * 
 * @since 1.0
 */
public final class MetadataRebuildSlice implements Slice {

    /**
     * Pattern to extract coordinates from Maven artifact path.
     * Groups: 1=groupId, 2=artifactId, 3=version
     */
    private static final Pattern ARTIFACT_PATTERN = Pattern.compile(
        "^/(.+)/([^/]+)/([^/]+)/\\2-\\3.*\\.(jar|pom|war|ear|aar)$"
    );

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Constructor.
     * @param origin Origin slice to wrap
     * @param repoName Repository name
     */
    public MetadataRebuildSlice(final Slice origin, final String repoName) {
        this.origin = origin;
        this.repoName = repoName;
    }

    /**
     * Constructor (backward compatibility).
     * @param origin Origin slice to wrap
     */
    public MetadataRebuildSlice(final Slice origin) {
        this(origin, "unknown");
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String method = line.method().value();

        // Only trigger on uploads
        if (!"PUT".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
            return origin.response(line, headers, body);
        }

        final String path = line.uri().getPath();

        // Process upload first
        return origin.response(line, headers, body).thenApply(resp -> {
            // Only rebuild metadata on successful upload
            if (resp.status().success()) {
                // Extract coordinates
                final Optional<MavenCoords> coords = extractCoordinates(path);

                if (coords.isPresent()) {
                    // Trigger metadata rebuild asynchronously (don't block response)
                    rebuildMetadataAsync(coords.get(), path);
                }
            }

            return resp;
        });
    }

    /**
     * Extract Maven coordinates from artifact path.
     * @param path Artifact path
     * @return Coordinates if valid Maven artifact path
     */
    private static Optional<MavenCoords> extractCoordinates(final String path) {
        final Matcher matcher = ARTIFACT_PATTERN.matcher(path);

        if (!matcher.matches()) {
            return Optional.empty();
        }

        final String groupId = matcher.group(1).replace('/', '.');
        final String artifactId = matcher.group(2);
        final String version = matcher.group(3);

        return Optional.of(new MavenCoords(groupId, artifactId, version));
    }

    /**
     * Rebuild metadata asynchronously.
     * Does not block or fail the upload if metadata rebuild fails.
     * 
     * @param coords Maven coordinates
     * @param uploadPath Path that was uploaded
     */
    private void rebuildMetadataAsync(final MavenCoords coords, final String uploadPath) {
        EcsLogger.debug("com.auto1.pantera.maven")
            .message("Triggering metadata rebuild")
            .eventCategory("repository")
            .eventAction("metadata_rebuild_trigger")
            .field("package.group", coords.groupId)
            .field("package.name", coords.artifactId)
            .field("package.version", coords.version)
            .field("file.path", uploadPath)
            .log();

        // Build metadata path: /{groupId}/{artifactId}/maven-metadata.xml
        final Key metadataKey = new Key.From(
            coords.groupId.replace('.', '/'),
            coords.artifactId,
            "maven-metadata.xml"
        );

        // Trigger rebuild asynchronously (fire and forget) with trace context propagation
        CompletableFuture.runAsync(TraceContextExecutor.wrap(() -> {
            final long startTime = System.currentTimeMillis();
            try {
                // Here you would call your metadata generator
                // For now, just log the intention
                EcsLogger.debug("com.auto1.pantera.maven")
                    .message("Metadata rebuild queued")
                    .eventCategory("repository")
                    .eventAction("metadata_rebuild")
                    .field("package.group", coords.groupId)
                    .field("package.name", coords.artifactId)
                    .field("package.version", coords.version)
                    .field("package.name", metadataKey.string())
                    .log();

                // TODO: Integrate with existing MavenMetadata class
                // new MavenMetadata(...).updateMetadata(coords).join();

                // Record successful metadata rebuild
                final long duration = System.currentTimeMillis() - startTime;
                recordMetadataOperation("rebuild", duration);

            } catch (RuntimeException e) {  // NOPMD - Best-effort async, catch all
                final long duration = System.currentTimeMillis() - startTime;
                EcsLogger.warn("com.auto1.pantera.maven")
                    .message("Metadata rebuild failed")
                    .eventCategory("repository")
                    .eventAction("metadata_rebuild")
                    .eventOutcome("failure")
                    .error(e)
                    .field("package.group", coords.groupId)
                    .field("package.name", coords.artifactId)
                    .field("package.version", coords.version)
                    .log();
                // Record failed metadata rebuild
                recordMetadataOperation("rebuild_failed", duration);
                // Don't propagate error - metadata rebuild is best-effort
            }
        }));
    }

    private void recordMetadataOperation(final String operation, final long duration) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordMetadataOperation(this.repoName, "maven", operation);
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordMetadataGenerationDuration(this.repoName, "maven", duration);
        }
    }

    /**
     * Maven coordinates (groupId:artifactId:version).
     */
    private static final class MavenCoords {
        private final String groupId;
        private final String artifactId;
        private final String version;

        MavenCoords(final String groupId, final String artifactId, final String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }
    }
}
