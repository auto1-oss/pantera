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
package com.auto1.pantera.scheduling;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Login;

import java.util.Queue;

/**
 * Repository events.
 */
public final class RepositoryEvents {

    /**
     * Fallback version when none can be inferred.
     */
    public static final String VERSION = "UNKNOWN";

    /**
     * Repository type.
     */
    private final String rtype;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Artifact events queue.
     */
    private final Queue<ArtifactEvent> queue;

    /**
     * Ctor.
     * @param rtype Repository type
     * @param rname Repository name
     * @param queue Artifact events queue
     */
    public RepositoryEvents(
        final String rtype, final String rname, final Queue<ArtifactEvent> queue
    ) {
        this.rtype = rtype;
        this.rname = rname;
        this.queue = queue;
    }

    /**
     * Repository type this instance was constructed with.
     * @return Repository type
     */
    public String repoType() {
        return this.rtype;
    }

    /**
     * Repository name this instance was constructed with.
     * @return Repository name
     */
    public String repoName() {
        return this.rname;
    }

    /**
     * Format an artifact name from a storage key, using the same rule
     * {@link #addUploadEventByKey} and {@link #addDeleteEventByKey} use
     * internally. Exposed so callers (e.g. {@code SliceUpload}, {@code
     * SliceDelete}) can build an audit-log call with the exact same {@code
     * package.name} that will end up in the queued {@link ArtifactEvent}.
     * @param key Storage key
     * @return Formatted artifact name
     */
    public String artifactName(final Key key) {
        return this.formatArtifactName(key);
    }

    /**
     * Adds event to queue. For file/file-proxy repos the version is inferred
     * from the artifact name; for all other types it falls back to "UNKNOWN".
     * @param key Artifact key
     * @param size Artifact size
     * @param headers Request headers
     */
    public void addUploadEventByKey(final Key key, final long size,
        final Headers headers) {
        final String aname = formatArtifactName(key);
        final String version = detectFileVersion(this.rtype, aname);
        this.queue.add( // ok: unbounded ConcurrentLinkedDeque (ArtifactEvent queue)
            new ArtifactEvent(
                this.rtype, this.rname, new Login(headers).getValue(),
                aname, version, size, System.currentTimeMillis(), null,
                this.storagePath(key)
            )
        );
    }

    /**
     * Adds event to queue, artifact name is the key and version is "UNKNOWN",
     * owner is obtained from headers.
     * @param key Artifact key
     */
    public void addDeleteEventByKey(final Key key) {
        final String aname = formatArtifactName(key);
        this.queue.add( // ok: unbounded ConcurrentLinkedDeque (ArtifactEvent queue)
            new ArtifactEvent(this.rtype, this.rname, aname, RepositoryEvents.VERSION)
        );
    }

    /**
     * Infer a version for file-type repositories from the dotted artifact name.
     * Delegates to {@link FileVersionDetector#detect(String)}.
     *
     * <p>Returns {@code "UNKNOWN"} for non-file repo types or when no
     * version-like token run is found.</p>
     *
     * @param rtype Repository type
     * @param name Dotted artifact name
     * @return Detected version or {@code "UNKNOWN"}
     */
    public static String detectFileVersion(final String rtype, final String name) {
        if (!"file".equals(rtype) && !"file-proxy".equals(rtype)) {
            return RepositoryEvents.VERSION;
        }
        return FileVersionDetector.detect(name);
    }

    /**
     * Format artifact name from storage key depending on repository type.
     * For file-based repositories, convert path separators to dots and exclude repo name prefix.
     * For other repository types, keep the key string as-is.
     * @param key Storage key
     * @return Formatted artifact name
     */
    private String formatArtifactName(final Key key) {
        if ("file".equals(this.rtype) || "file-proxy".equals(this.rtype)) {
            // Flattened display name. The separators are destroyed here, which
            // is why the real key is carried alongside as the event's
            // pathPrefix — a dotted name cannot be reversed into a path
            // (filenames and versions legitimately contain dots).
            return this.storagePath(key).replace('/', '.');
        }
        return key.string();
    }

    /**
     * The artifact's real repo-relative storage key — what the tree browser
     * needs to navigate to the artifact's directory.
     * @param key Storage key
     * @return Repo-relative path, no leading slash, no repository-name prefix
     */
    private String storagePath(final Key key) {
        String name = key.string();
        // Strip leading slash if any (defensive; KeyFromPath already removes it)
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        // Exclude repo name prefix if present
        if (this.rname != null && !this.rname.isEmpty() && name.startsWith(this.rname + "/")) {
            name = name.substring(this.rname.length() + 1);
        }
        return name;
    }
}
