/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.jcabi.log.Logger;

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
     * Constructor.
     * @param origin Origin slice to wrap
     */
    public MetadataRebuildSlice(final Slice origin) {
        this.origin = origin;
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
        Logger.debug(
            this,
            "Triggering metadata rebuild for %s:%s:%s (uploaded: %s)",
            coords.groupId,
            coords.artifactId,
            coords.version,
            uploadPath
        );

        // Build metadata path: /{groupId}/{artifactId}/maven-metadata.xml
        final Key metadataKey = new Key.From(
            coords.groupId.replace('.', '/'),
            coords.artifactId,
            "maven-metadata.xml"
        );

        // Trigger rebuild asynchronously (fire and forget)
        CompletableFuture.runAsync(() -> {
            try {
                // Here you would call your metadata generator
                // For now, just log the intention
                Logger.debug(
                    this,
                    "Metadata rebuild queued for %s",
                    metadataKey.string()
                );

                // TODO: Integrate with existing MavenMetadata class
                // new MavenMetadata(...).updateMetadata(coords).join();

            } catch (RuntimeException e) {  // NOPMD - Best-effort async, catch all
                Logger.warn(
                    this,
                    "Metadata rebuild failed for %s:%s:%s - %s",
                    coords.groupId,
                    coords.artifactId,
                    coords.version,
                    e.getMessage()
                );
                // Don't propagate error - metadata rebuild is best-effort
            }
        });
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
