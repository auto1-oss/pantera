/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Meta;
import com.artipie.asto.Storage;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.auth.Authentication;
import com.artipie.http.auth.BasicAuthzSlice;
import com.artipie.http.auth.OperationControl;
import com.artipie.http.headers.ContentLength;
import com.artipie.http.headers.Login;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rt.MethodRule;
import com.artipie.http.rt.RtRule;
import com.artipie.http.rt.RtRulePath;
import com.artipie.http.rt.SliceRoute;
import com.artipie.http.slice.KeyFromPath;
import com.artipie.http.slice.SliceSimple;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.security.perms.Action;
import com.artipie.security.perms.AdapterBasicPermission;
import com.artipie.security.policy.Policy;
import com.jcabi.log.Logger;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle repository HTTP slice.
 *
 * @since 1.0
 */
public final class GradleSlice extends Slice.Wrap {

    /**
     * Pattern to match Gradle artifacts.
     * Matches: /group/artifact/version/artifact-version[-classifier].extension
     */
    static final Pattern ARTIFACT = Pattern.compile(
        "^/(?<path>(?<group>[^/]+(?:/[^/]+)*)/(?<artifact>[^/]+)/(?<version>[^/]+)/[^/]+)$"
    );

    /**
     * Ctor.
     *
     * @param storage Storage
     * @param policy Security policy
     * @param auth Authentication
     * @param name Repository name
     * @param events Artifact events queue
     */
    public GradleSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication auth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        super(
            new BasicAuthzSlice(
                new SliceRoute(
                    new RtRulePath(
                        MethodRule.GET,
                        new DownloadSlice(storage, events, name)
                    ),
                    new RtRulePath(
                        MethodRule.HEAD,
                        new HeadSlice(storage)
                    ),
                    new RtRulePath(
                        MethodRule.PUT,
                        new UploadSlice(storage, events, name)
                    ),
                    new RtRulePath(
                        RtRule.FALLBACK,
                        new SliceSimple(ResponseBuilder.methodNotAllowed().build())
                    )
                ),
                auth,
                new OperationControl(
                    policy,
                    new AdapterBasicPermission(name, Action.Standard.READ),
                    new AdapterBasicPermission(name, Action.Standard.WRITE)
                )
            )
        );
    }

    /**
     * Download slice.
     */
    private static final class DownloadSlice implements Slice {

        /**
         * Storage.
         */
        private final Storage storage;

        /**
         * Artifact events queue.
         */
        private final Optional<Queue<ArtifactEvent>> events;

        /**
         * Repository name.
         */
        private final String rname;

        /**
         * Ctor.
         *
         * @param storage Storage
         * @param events Artifact events queue
         * @param rname Repository name
         */
        DownloadSlice(
            final Storage storage,
            final Optional<Queue<ArtifactEvent>> events,
            final String rname
        ) {
            this.storage = storage;
            this.events = events;
            this.rname = rname;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line,
            final Headers headers,
            final Content body
        ) {
            final Key key = new KeyFromPath(line.uri().getPath());
            return this.storage.exists(key).thenCompose(
                exists -> {
                    if (exists) {
                        this.addEvent(key, new Login(headers).getValue());
                        return this.storage.value(key).thenCompose(
                            pub -> this.storage.metadata(key).thenApply(
                                meta -> {
                                    final ResponseBuilder builder = ResponseBuilder.ok()
                                        .body(pub);
                                    meta.read(Meta.OP_SIZE).ifPresent(
                                        size -> builder.header(new ContentLength(size))
                                    );
                                    builder.header("Content-Type", contentType(key.string()));
                                    return builder.build();
                                }
                            )
                        );
                    }
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.notFound().build()
                    );
                }
            );
        }

        /**
         * Add artifact event to queue.
         *
         * @param key Artifact key
         * @param owner Owner
         */
        private void addEvent(final Key key, final String owner) {
            if (this.events.isPresent()) {
                final String path = key.string();
                
                // Skip maven-metadata.xml files and their checksums - they're metadata about versions, not actual artifacts
                if (isMavenMetadataFile(path)) {
                    Logger.debug(this, "Skipping maven-metadata file for event: %s", path);
                    return;
                }
                
                final Matcher matcher = ARTIFACT.matcher(path);
                if (matcher.matches()) {
                    final String group = matcher.group("group");
                    final String artifact = matcher.group("artifact");
                    final String version = matcher.group("version");
                    this.events.get().add(
                        new ArtifactEvent(
                            "gradle",
                            this.rname,
                            owner,
                            String.format("%s:%s", group.replace('/', '.'), artifact),
                            version,
                            0L
                        )
                    );
                }
            }
        }
    }

    /**
     * HEAD slice.
     */
    private static final class HeadSlice implements Slice {

        /**
         * Storage.
         */
        private final Storage storage;

        /**
         * Ctor.
         *
         * @param storage Storage
         */
        HeadSlice(final Storage storage) {
            this.storage = storage;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line,
            final Headers headers,
            final Content body
        ) {
            final Key key = new KeyFromPath(line.uri().getPath());
            return this.storage.exists(key).thenCompose(
                exists -> {
                    if (exists) {
                        return this.storage.metadata(key).thenApply(
                            meta -> {
                                final ResponseBuilder builder = ResponseBuilder.ok();
                                meta.read(Meta.OP_SIZE).ifPresent(
                                    size -> builder.header(new ContentLength(size))
                                );
                                builder.header("Content-Type", contentType(key.string()));
                                return builder.build();
                            }
                        );
                    }
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.notFound().build()
                    );
                }
            );
        }
    }

    /**
     * Upload slice.
     */
    private static final class UploadSlice implements Slice {

        /**
         * Storage.
         */
        private final Storage storage;

        /**
         * Artifact events queue.
         */
        private final Optional<Queue<ArtifactEvent>> events;

        /**
         * Repository name.
         */
        private final String rname;

        /**
         * Ctor.
         *
         * @param storage Storage
         * @param events Artifact events queue
         * @param rname Repository name
         */
        UploadSlice(
            final Storage storage,
            final Optional<Queue<ArtifactEvent>> events,
            final String rname
        ) {
            this.storage = storage;
            this.events = events;
            this.rname = rname;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line,
            final Headers headers,
            final Content body
        ) {
            // Strip semicolon-separated metadata properties from the path to avoid exceeding
            // filesystem filename length limits (typically 255 bytes). These properties are
            // added by JFrog Artifactory and Gradle build tools (e.g., vcs.revision, build.timestamp)
            // but are not part of the actual artifact filename.
            final String path = line.uri().getPath();
            final String sanitizedPath;
            final int semicolonIndex = path.indexOf(';');
            if (semicolonIndex > 0) {
                sanitizedPath = path.substring(0, semicolonIndex);
                Logger.debug(
                    this,
                    "Stripped metadata properties from path: %s -> %s",
                    path,
                    sanitizedPath
                );
            } else {
                sanitizedPath = path;
            }

            final Key key = new KeyFromPath(sanitizedPath);
            final String owner = new Login(headers).getValue();
            // Get content length from headers for database record
            final long size = headers.stream()
                .filter(h -> "Content-Length".equalsIgnoreCase(h.getKey()))
                .findFirst()
                .map(h -> Long.parseLong(h.getValue()))
                .orElse(0L);
            
            return this.storage.save(key, body).thenApply(
                nothing -> {
                    this.addEvent(key, owner, size);
                    return ResponseBuilder.created().build();
                }
            ).exceptionally(
                throwable -> {
                    Logger.error(this, "Failed to save artifact: %s", throwable.getMessage());
                    return ResponseBuilder.internalError().build();
                }
            );
        }

        /**
         * Add artifact event to queue.
         *
         * @param key Artifact key
         * @param owner Owner
         * @param size Artifact size
         */
        private void addEvent(final Key key, final String owner, final long size) {
            if (this.events.isPresent()) {
                // Ensure path starts with / for pattern matching
                final String path = key.string().startsWith("/") ? key.string() : "/" + key.string();
                
                // Skip maven-metadata.xml files and their checksums - they're metadata about versions, not actual artifacts
                if (isMavenMetadataFile(path)) {
                    Logger.debug(this, "Skipping maven-metadata file for event: %s", path);
                    return;
                }
                
                final Matcher matcher = ARTIFACT.matcher(path);
                if (matcher.matches()) {
                    final String group = matcher.group("group");
                    final String artifact = matcher.group("artifact");
                    final String version = matcher.group("version");
                    final long created = System.currentTimeMillis();
                    this.events.get().add(
                        new ArtifactEvent(
                            "gradle",
                            this.rname,
                            owner == null || owner.isBlank() ? ArtifactEvent.DEF_OWNER : owner,
                            String.format("%s:%s", group.replace('/', '.'), artifact),
                            version,
                            size,
                            created,
                            (Long) null  // No release date for uploads
                        )
                    );
                    Logger.info(
                        this,
                        "Recorded Gradle upload: %s:%s:%s (size=%d, owner=%s)",
                        group.replace('/', '.'), artifact, version, size, owner
                    );
                } else {
                    Logger.debug(this, "Path %s did not match artifact pattern for event", path);
                }
            }
        }
    }

    /**
     * Check if the path is a maven-metadata.xml file or its checksum.
     *
     * @param path File path
     * @return True if it's a maven-metadata file
     */
    private static boolean isMavenMetadataFile(final String path) {
        return path.endsWith("/maven-metadata.xml") 
            || path.endsWith("maven-metadata.xml")
            || path.endsWith("/maven-metadata.xml.md5")
            || path.endsWith("/maven-metadata.xml.sha1")
            || path.endsWith("/maven-metadata.xml.sha256")
            || path.endsWith("/maven-metadata.xml.sha512");
    }

    /**
     * Determine content type from file extension.
     *
     * @param path File path
     * @return Content type header
     */
    private static String contentType(final String path) {
        final String type;
        if (path.endsWith(".jar") || path.endsWith(".aar")) {
            type = "application/java-archive";
        } else if (path.endsWith(".pom")) {
            type = "application/xml";
        } else if (path.endsWith(".module")) {
            type = "application/json";
        } else if (path.endsWith(".sha1") || path.endsWith(".sha256") || path.endsWith(".sha512") || path.endsWith(".md5")) {
            type = "text/plain";
        } else {
            type = "application/octet-stream";
        }
        return type;
    }
}
