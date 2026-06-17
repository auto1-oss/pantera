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
import com.auto1.pantera.http.log.EcsMdc;
import java.util.Optional;
import java.util.Objects;
import org.slf4j.MDC;

/**
 * Proxy artifact event contains artifact key in storage,
 * repository name and artifact owner login.
 *
 * <p>The {@link #repoType()} field was added in 2.2.0 so the downstream
 * {@code ArtifactEvent} carries the actual repo_type of the repo that admitted
 * the artifact (e.g. {@code gradle-proxy} vs {@code maven-proxy}, which share
 * the same Maven slice via {@code RepositorySlices}). Pre-2.2.0 the
 * package-processor hardcoded a literal; that broke cooldown lookups for
 * {@code gradle-proxy} because rows landed under {@code repo_type='maven-proxy'}
 * but the cooldown evaluator queried with the correct repo type.
 *
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
     * Repository type (e.g. {@code maven-proxy}, {@code gradle-proxy}). May be
     * {@code null} for back-compat with callers built before 2.2.0.
     */
    private final String rtype;

    /**
     * Artifact owner name.
     */
    private final String owner;

    /**
     * Optional release timestamp in milliseconds since epoch.
     */
    private final Optional<Long> release;

    /**
     * Originating request's {@code trace.id} — captured from MDC at
     * construction time so the downstream {@code ArtifactEvent} (built
     * later on the package-processor worker thread, where MDC is empty)
     * can carry the request's trace id forward to the audit log.
     * Nullable.
     */
    private final String traceId;

    /**
     * Originating client IP — captured from MDC at construction time so
     * the audit log entry written from the consumer thread carries the
     * IP of the HTTP caller that triggered the publish. Without this,
     * the chain is: request thread (MDC populated) → ProxyArtifactEvent
     * queue → package processor (worker, no MDC) → ArtifactEvent
     * (MDC.get returns null) → audit log loses client.ip. Nullable.
     */
    private final String clientIp;

    /**
     * Ctor.
     * @param key Artifact key
     * @param rname Repository name
     * @param owner Artifact owner name
     */
    public ProxyArtifactEvent(final Key key, final String rname, final String owner) {
        this(key, rname, null, owner, Optional.empty());
    }

    /**
     * Ctor.
     * @param key Artifact key
     * @param rname Repository name
     */
    public ProxyArtifactEvent(final Key key, final String rname) {
        this(key, rname, null, ArtifactEvent.DEF_OWNER, Optional.empty());
    }

    /**
     * Ctor (back-compat, no repo_type).
     * @param key Artifact key
     * @param rname Repository name
     * @param owner Artifact owner name
     * @param release Release timestamp in millis since epoch (optional)
     */
    public ProxyArtifactEvent(final Key key, final String rname, final String owner, final Optional<Long> release) {
        this(key, rname, null, owner, release);
    }

    /**
     * Full ctor with explicit repo_type.
     * @param key Artifact key
     * @param rname Repository name
     * @param rtype Repository type (may be {@code null} for legacy callers)
     * @param owner Artifact owner name
     * @param release Release timestamp in millis since epoch (optional)
     */
    public ProxyArtifactEvent(
        final Key key, final String rname, final String rtype,
        final String owner, final Optional<Long> release
    ) {
        this.key = key;
        this.rname = rname;
        this.rtype = rtype;
        this.owner = owner;
        this.release = release == null ? Optional.empty() : release;
        // Auto-capture request-thread context. ProxyArtifactEvent is built
        // synchronously by the proxy slice's response handler, which runs
        // on the request thread with MDC populated by EcsLoggingSlice.
        // These fields are forwarded to the downstream ArtifactEvent
        // (see PackageProcessor.toArtifactEvent) so the audit log written
        // from the consumer thread carries trace.id + client.ip.
        this.traceId = MDC.get(EcsMdc.TRACE_ID);
        this.clientIp = MDC.get(EcsMdc.CLIENT_IP);
    }

    /**
     * Originating request {@code trace.id}, captured from MDC at
     * construction time. {@code null} when the event was built outside a
     * request scope (rare — pretty much only scheduler-driven recovery).
     */
    public String traceId() {
        return this.traceId;
    }

    /**
     * Originating client IP, captured from MDC at construction time.
     * {@code null} when EcsLoggingSlice could not resolve a client IP for
     * the request (e.g. an internal-routing call with no source header).
     */
    public String clientIp() {
        return this.clientIp;
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
     * Obtain repository type (e.g. {@code gradle-proxy}). May return
     * {@code null} when the event was constructed by a legacy caller that
     * predates the repo_type propagation fix; downstream consumers must
     * apply an adapter-specific fallback (or simply drop the event) in that
     * case.
     * @return Repository type, or {@code null} for legacy events
     */
    public String repoType() {
        return this.rtype;
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
