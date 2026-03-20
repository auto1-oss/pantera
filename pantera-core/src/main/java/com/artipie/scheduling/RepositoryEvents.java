/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.scheduling;

import com.artipie.asto.Key;
import com.artipie.http.Headers;
import com.artipie.http.headers.Login;

import java.util.Queue;

/**
 * Repository events.
 */
public final class RepositoryEvents {

    /**
     * Unknown version.
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
     * Adds event to queue, artifact name is the key and version is "UNKNOWN",
     * owner is obtained from headers.
     * @param key Artifact key
     * @param size Artifact size
     * @param headers Request headers
     */
    public void addUploadEventByKey(final Key key, final long size,
        final Headers headers) {
        final String aname = formatArtifactName(key);
        this.queue.add(
            new ArtifactEvent(
                this.rtype, this.rname, new Login(headers).getValue(),
                aname, RepositoryEvents.VERSION, size
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
