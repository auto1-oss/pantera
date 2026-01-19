/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.scheduling;

import com.artipie.asto.Key;
import java.util.Optional;
import java.util.Objects;

/**
 * Proxy artifact event contains artifact key in storage,
 * repository name and artifact owner login.
 * @since 1.3
 */
public final class ProxyArtifactEvent {

    /**
     * Artifact key.
     */
    private final Key key;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Artifact owner name.
     */
    private final String owner;

    /**
     * Optional release timestamp in milliseconds since epoch.
     */
    private final Optional<Long> release;

    /**
     * Ctor.
     * @param key Artifact key
     * @param rname Repository name
     * @param owner Artifact owner name
     */
    public ProxyArtifactEvent(final Key key, final String rname, final String owner) {
        this(key, rname, owner, Optional.empty());
    }

    /**
     * Ctor.
     * @param key Artifact key
     * @param rname Repository name
     */
    public ProxyArtifactEvent(final Key key, final String rname) {
        this(key, rname, ArtifactEvent.DEF_OWNER, Optional.empty());
    }

    /**
     * Ctor.
     * @param key Artifact key
     * @param rname Repository name
     * @param owner Artifact owner name
     * @param release Release timestamp in millis since epoch (optional)
     */
    public ProxyArtifactEvent(final Key key, final String rname, final String owner, final Optional<Long> release) {
        this.key = key;
        this.rname = rname;
        this.owner = owner;
        this.release = release == null ? Optional.empty() : release;
    }

    /**
     * Optional release timestamp in milliseconds.
     * @return Optional timestamp
     */
    public Optional<Long> releaseMillis() {
        return this.release;
    }

    /**
     * Obtain artifact key.
     * @return The key
     */
    public Key artifactKey() {
        return this.key;
    }

    /**
     * Obtain repository name.
     * @return Repository name
     */
    public String repoName() {
        return this.rname;
    }

    /**
     * Login of the owner.
     * @return Owner login
     */
    public String ownerLogin() {
        return this.owner;
    }

    @Override
    public boolean equals(final Object other) {
        final boolean res;
        if (this == other) {
            res = true;
        } else if (other == null || getClass() != other.getClass()) {
            res = false;
        } else {
            final ProxyArtifactEvent that = (ProxyArtifactEvent) other;
            res = this.key.equals(that.key) && this.rname.equals(that.rname);
        }
        return res;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.key, this.rname);
    }
}
