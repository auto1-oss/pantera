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
     * Infer a version for file-type repositories from the dotted artifact
     * name. The name is the storage path with {@code /} replaced by {@code .},
     * e.g. {@code a.b.1.5.0-SNAPSHOT.artifact-1.5.0.jar}. The first
     * contiguous run of dot-tokens starting with a digit is treated as the
     * version directory and returned.
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
        if (name == null || name.isEmpty()) {
            return RepositoryEvents.VERSION;
        }
        final String[] tokens = name.split("\\.");
        int start = -1;
        int end = -1;
        for (int i = 0; i < tokens.length; i++) {
            final String tok = tokens[i];
            if (!tok.isEmpty() && isVersionToken(tok)) {
                if (start == -1) {
                    start = i;
                }
                end = i;
            } else if (start != -1) {
                break;
            }
        }
        if (start == -1) {
            return RepositoryEvents.VERSION;
        }
        // Check if preceding token ends with -{digits} — those digits are
        // the real version start, split away by dot tokenization.
        // e.g. "elinks-current-0" + "11" → version = "0.11"
        final StringBuilder sb = new StringBuilder();
        if (start > 0) {
            final String prev = tokens[start - 1];
            final int lastHyphen = prev.lastIndexOf('-');
            if (lastHyphen >= 0 && lastHyphen < prev.length() - 1) {
                final String tail = prev.substring(lastHyphen + 1);
                if (isVersionToken(tail)) {
                    sb.append(tail).append('.');
                }
            }
        }
        for (int i = start; i <= end; i++) {
            if (i > start) {
                sb.append('.');
            }
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    /**
     * Returns true if the dot-split token looks like part of a version:
     * starts with a digit, or starts with {@code v} followed by a digit.
     * @param token Token to check
     * @return True if version-like
     */
    private static boolean isVersionToken(final String token) {
        if (token.isEmpty()) {
            return false;
        }
        final char first = token.charAt(0);
        if (Character.isDigit(first)) {
            return true;
        }
        return first == 'v' && token.length() > 1
            && Character.isDigit(token.charAt(1));
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
