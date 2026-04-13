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
    private static final String VERSION = "UNKNOWN";

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
        this.queue.add(
            new ArtifactEvent(
                this.rtype, this.rname, new Login(headers).getValue(),
                aname, version, size
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
        this.queue.add(
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
        final String raw = key.string();
        if ("file".equals(this.rtype) || "file-proxy".equals(this.rtype)) {
            String name = raw;
            // Strip leading slash if any (defensive; KeyFromPath already removes it)
            if (name.startsWith("/")) {
                name = name.substring(1);
            }
            // Exclude repo name prefix if present
            if (this.rname != null && !this.rname.isEmpty() && name.startsWith(this.rname + "/")) {
                name = name.substring(this.rname.length() + 1);
            }
            // Replace folder separators with dots
            return name.replace('/', '.');
        }
        return raw;
    }
}
