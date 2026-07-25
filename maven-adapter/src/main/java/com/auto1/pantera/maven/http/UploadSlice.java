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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.ContentWithSize;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.maven.metadata.MavenMetadataRegenerator;
import com.auto1.pantera.maven.metadata.MavenTimestamp;
import com.auto1.pantera.maven.metadata.Version;
import com.auto1.pantera.maven.security.KeyringStoreRegistry;
import com.auto1.pantera.maven.security.PgpVerifier;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.jcabi.xml.XMLDocument;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.MDC;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Simple upload slice that saves files directly to storage, similar to Gradle adapter.
 * No temporary directories, no complex validation - just save and optionally emit events.
 * @since 0.8
 */
public final class UploadSlice implements Slice {

    /**
     * Supported checksum algorithms.
     */
    private static final List<String> CHECKSUM_ALGS = Arrays.asList("sha512", "sha256", "sha1", "md5");

    /**
     * Detached-signature suffixes recognised by {@link #isSignatureSidecar}.
     */
    private static final List<String> SIGNATURE_SUFFIXES = List.of(".asc", ".sig");

    /**
     * Key prefix under which a primary artifact is quarantined while
     * {@code verifyPgp} is enabled and no verified signature has committed
     * it to its real (servable) key yet (H1 fix). Chosen so it can never
     * collide with a real Maven GAV path: no valid Maven groupId/artifactId
     * segment starts with {@code .}, and {@link MavenMetadataRegenerator}'s
     * per-GA {@code storage.list(baseKey)} scan for {@code <versions>}
     * never descends into this entirely separate top-level prefix, so a
     * staged (unverified) primary can never be counted as a published
     * version.
     *
     * <p>Package-private (not {@code private}): {@link MavenSlice} reads
     * this to install a routing-level guard rejecting any request whose
     * path addresses this namespace directly — {@code Storage} is a flat
     * key/value space, so without that guard a client could {@code GET
     * /.pgp-pending/<real-path>} and read a staged, not-yet-verified
     * primary straight out of quarantine, or {@code PUT} into it directly.
     * Staging alone (hiding the path from normal listings) is not
     * sufficient; the path must be unaddressable.
     */
    static final String STAGING_PREFIX = ".pgp-pending";

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
     * Synchronous artifact-index writer. Runs inline with upload so the
     * group resolver's index lookup sees the new artifact immediately —
     * no stale-index window. Defaults to {@link SyncArtifactIndexer#NOOP}
     * when no DB is configured.
     */
    private final SyncArtifactIndexer syncIndex;

    /**
     * Hosted-write policy (WS4-maven.2/.6): {@code verifyPgp} and
     * {@code releaseImmutable}. Defaults to {@link MavenHostedPolicy#DEFAULT}
     * (byte-identical to pre-2.3.0 behaviour) for every ctor overload that
     * predates this flag.
     */
    private final MavenHostedPolicy policy;

    /**
     * Ctor without events.
     * @param storage Abstract storage
     */
    public UploadSlice(final Storage storage) {
        this(storage, Optional.empty(), "maven", SyncArtifactIndexer.NOOP, MavenHostedPolicy.DEFAULT);
    }

    /**
     * Legacy ctor — no synchronous index writer. Kept for callers that
     * have not been updated yet; tests use this overload.
     * @param storage Storage
     * @param events Artifact events queue
     * @param rname Repository name
     */
    public UploadSlice(
        final Storage storage,
        final Optional<Queue<ArtifactEvent>> events,
        final String rname
    ) {
        this(storage, events, rname, SyncArtifactIndexer.NOOP, MavenHostedPolicy.DEFAULT);
    }

    /**
     * Ctor with synchronous index writer.
     * @param storage Storage
     * @param events Artifact events queue
     * @param rname Repository name
     * @param syncIndex Synchronous artifact-index writer
     */
    public UploadSlice(
        final Storage storage,
        final Optional<Queue<ArtifactEvent>> events,
        final String rname,
        final SyncArtifactIndexer syncIndex
    ) {
        this(storage, events, rname, syncIndex, MavenHostedPolicy.DEFAULT);
    }

    /**
     * Ctor with synchronous index writer AND hosted-write policy
     * (WS4-maven.2/.6). The single field-initializing constructor — every
     * other overload delegates here with {@link MavenHostedPolicy#DEFAULT}.
     * @param storage Storage
     * @param events Artifact events queue
     * @param rname Repository name
     * @param syncIndex Synchronous artifact-index writer
     * @param policy Hosted-write policy
     */
    public UploadSlice(
        final Storage storage,
        final Optional<Queue<ArtifactEvent>> events,
        final String rname,
        final SyncArtifactIndexer syncIndex,
        final MavenHostedPolicy policy
    ) {
        this.storage = storage;
        this.events = events;
        this.rname = rname;
        this.syncIndex = syncIndex;
        this.policy = policy;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Strip semicolon-separated metadata properties from the path to avoid exceeding
        // filesystem filename length limits (typically 255 bytes). These properties are
        // added by JFrog Artifactory and Maven build tools (e.g., vcs.revision, build.timestamp)
        // but are not part of the actual artifact filename.
        final String path = line.uri().getPath();
        final String sanitizedPath;
        final int semicolonIndex = path.indexOf(';');
        if (semicolonIndex > 0) {
            sanitizedPath = path.substring(0, semicolonIndex);
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Stripped metadata properties from path: " + path + " -> " + sanitizedPath)
                .eventCategory("web")
                .eventAction("path_sanitization")
                .field("log.source", "application")
                .log();
        } else {
            sanitizedPath = path;
        }

        final Key key = new KeyFromPath(sanitizedPath);
        final String owner = new Login(headers).getValue();
        
        // Get content length from headers for event record
        final long size = headers.stream()
            .filter(h -> "Content-Length".equalsIgnoreCase(h.getKey()))
            .findFirst()
            .map(h -> Long.parseLong(h.getValue()))
            .orElse(0L);
        
        // Track upload metric
        this.recordMetric(() ->
            com.auto1.pantera.metrics.PanteraMetrics.instance().upload(this.rname, "maven")
        );

        // Track bandwidth (upload)
        if (size > 0) {
            this.recordMetric(() ->
                com.auto1.pantera.metrics.PanteraMetrics.instance().bandwidth(this.rname, "maven", "upload", size)
            );
        }
        
        final String keyPath = key.string();
        // Captured before any async hop, per CLAUDE.md — used by the
        // checksum-mismatch / release-immutability / pgp-verification-failed
        // audit paths below.
        final AuditContext auditCtx = this.captureAuditContext(headers);

        // Special handling for maven-metadata.xml - fix it BEFORE saving
        if (isMetadataXmlContent(keyPath)) {
            return this.handleMetadataUpload(key, body, headers, owner, size, keyPath);
        }

        // For maven-metadata.xml checksums, SKIP them - we generated our own
        if (isMetadataXmlChecksum(keyPath)) {
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Skipping Maven-uploaded checksum for metadata (using generated checksums)")
                .eventCategory("web")
                .eventAction("checksum_upload")
                .field("package.path", keyPath)
                .field("log.source", "application")
                .log();
            // Don't save Maven's checksums - we already generated correct ones
            return CompletableFuture.completedFuture(ResponseBuilder.created().build());
        }

        // WS4-maven.5: a checksum sidecar for a real (non-metadata) primary —
        // verify against the server-computed digest of the ALREADY-STORED
        // primary before persisting the client's claimed value.
        if (isChecksumSidecar(keyPath)) {
            return this.handleChecksumSidecarUpload(key, keyPath, body, headers, owner, size, auditCtx);
        }

        // WS4-maven.2 (hosted half): verify `.asc`/`.sig` against the
        // already-stored primary when verifyPgp is enabled for this repo.
        if (this.policy.verifyPgp() && isSignatureSidecar(keyPath)) {
            return this.handleSignatureUpload(key, keyPath, body, headers, owner, size, auditCtx);
        }

        return this.handlePrimaryUpload(key, keyPath, body, headers, owner, size, auditCtx);
    }

    /**
     * The client-uploaded {@code maven-metadata.xml} itself (not one of its
     * checksum sidecars). Normalises {@code <latest>}/{@code <lastUpdated>}
     * via {@link #fixMetadataBytes(byte[])} — unchanged from pre-2.3.0.
     * The GA-level {@code <versions>} listing this file advertises is
     * superseded on the next primary-artifact deploy for the same GA by
     * {@link #regenerateMetadataIfPrimary(String)}, which is the source of
     * truth WS4-maven.4 establishes; a bare metadata-only PUT (no
     * accompanying primary in the same request — e.g. an import script)
     * is still accepted and normalised so it degrades gracefully.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> handleMetadataUpload(
        final Key key, final Content body, final Headers headers,
        final String owner, final long size, final String keyPath
    ) {
        EcsLogger.debug("com.auto1.pantera.maven")
            .message("Intercepting maven-metadata.xml upload for fixing")
            .eventCategory("web")
            .eventAction("metadata_upload")
            .field("package.path", keyPath)
            .field("log.source", "application")
            .log();
        return new ContentWithSize(body, headers).asBytesFuture().thenCompose(
            bytes -> this.fixMetadataBytes(bytes).thenCompose(
                fixedBytes -> this.storage.save(key, new Content.From(fixedBytes)).thenCompose(
                    nothing -> {
                        EcsLogger.debug("com.auto1.pantera.maven")
                            .message("Saved fixed maven-metadata.xml, generating checksums")
                            .eventCategory("web")
                            .eventAction("metadata_upload")
                            .field("package.path", keyPath)
                            .field("log.source", "application")
                            .log();
                        return this.generateChecksums(key);
                    }
                )
            )
        ).thenCompose(
            sha256 -> this.addEvent(key, owner, size, sha256)
                .thenApply(ignored -> ResponseBuilder.created().build())
        ).exceptionally(
            throwable -> {
                EcsLogger.error("com.auto1.pantera.maven")
                    .message("Failed to save artifact")
                    .eventCategory("web")
                    .eventAction("artifact_upload")
                    .eventOutcome("failure")
                    .error(throwable)
                    .field("package.path", keyPath)
                    .field("log.source", "application")
                    .log();
                return ResponseBuilder.internalError().build();
            }
        );
    }

    /**
     * A primary artifact (jar/pom/war/aar/...) or a companion file that
     * isn't a checksum/signature sidecar (sources/javadoc jars). Applies
     * WS4-maven.6 release-redeploy immutability (checked against the real,
     * already-published key regardless of {@code verifyPgp} — a verified
     * release already served is still immutable), then either:
     * <ul>
     *   <li>{@code verifyPgp} enabled (H1 fix): quarantines the primary —
     *       see {@link #stagePrimaryForVerification} — instead of saving it
     *       to its servable location; it is promoted only once a matching
     *       verified {@code .asc}/{@code .sig} lands.</li>
     *   <li>{@code verifyPgp} disabled: saves, generates checksums,
     *       regenerates the GA {@code maven-metadata.xml} (WS4-maven.4),
     *       and records the {@link ArtifactEvent} — byte-identical to
     *       pre-2.3.0/pre-H1 behaviour.</li>
     * </ul>
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> handlePrimaryUpload(
        final Key key, final String keyPath, final Content body, final Headers headers,
        final String owner, final long size, final AuditContext auditCtx
    ) {
        final boolean snapshot = keyPath.contains("SNAPSHOT");
        final CompletableFuture<Optional<Response>> immutabilityCheck =
            this.policy.releaseImmutable() && !snapshot
                ? this.rejectIfReleaseExists(key, keyPath, owner, size, auditCtx)
                : CompletableFuture.completedFuture(Optional.empty());
        return immutabilityCheck.thenCompose(rejected -> {
            if (rejected.isPresent()) {
                return CompletableFuture.completedFuture(rejected.get());
            }
            if (this.policy.verifyPgp()) {
                return this.stagePrimaryForVerification(key, keyPath, body, headers, owner, size, auditCtx);
            }
            return this.saveAndRegenerate(key, keyPath, body, headers, owner, size);
        });
    }

    /**
     * WS4-maven.6: 409 + audit when {@code releaseImmutable} is on, the
     * path is a release (non-SNAPSHOT) coordinate, and the key already
     * exists — otherwise {@link Optional#empty()} (proceed with the save).
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Optional<Response>> rejectIfReleaseExists(
        final Key key, final String keyPath, final String owner, final long size,
        final AuditContext auditCtx
    ) {
        return this.storage.exists(key).thenApply(exists -> {
            if (!exists) {
                return Optional.<Response>empty();
            }
            final GavCoordinates gav = GavCoordinates.parse(keyPath).orElse(null);
            EcsLogger.warn("com.auto1.pantera.maven")
                .message("Rejected release redeploy: releaseImmutable is enabled and "
                    + keyPath + " already exists")
                .eventCategory("file")
                .eventAction("release_redeploy_rejected")
                .eventOutcome("failure")
                .field("repository.name", this.rname)
                .field("package.path", keyPath)
                .field("log.source", "application")
                .log();
            AuditLogger.publish(
                auditCtx, "maven", this.rname,
                gav != null ? gav.artifactName() : keyPath,
                gav != null ? gav.version() : null,
                size, owner, null, null,
                AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_CHECKSUM_MISMATCH
            );
            return Optional.of(
                ResponseBuilder.from(com.auto1.pantera.http.RsStatus.CONFLICT).build()
            );
        });
    }

    /**
     * Save the primary, generate its checksums, regenerate the GA metadata
     * (primary-artifact paths only), and record the {@link ArtifactEvent}.
     * Only reached when {@code verifyPgp} is disabled for this repo — the
     * {@code verifyPgp}-enabled path quarantines instead (see
     * {@link #stagePrimaryForVerification}), promoting into this same
     * checksums/metadata/event tail via {@link #publishPrimary} once a
     * verified signature commits it.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> saveAndRegenerate(
        final Key key, final String keyPath, final Content body, final Headers headers,
        final String owner, final long size
    ) {
        return this.storage.save(key, new ContentWithSize(body, headers)).thenCompose(
            nothing -> {
                EcsLogger.debug("com.auto1.pantera.maven")
                    .message("Saved artifact file")
                    .eventCategory("web")
                    .eventAction("artifact_upload")
                    .field("package.path", keyPath)
                    .field("package.size", size)
                    .field("log.source", "application")
                    .log();
                return this.publishPrimary(key, keyPath, owner, size);
            }
        ).exceptionally(
            throwable -> {
                EcsLogger.error("com.auto1.pantera.maven")
                    .message("Failed to save artifact")
                    .eventCategory("web")
                    .eventAction("artifact_upload")
                    .eventOutcome("failure")
                    .error(throwable)
                    .field("package.path", keyPath)
                    .field("log.source", "application")
                    .log();
                return ResponseBuilder.internalError().build();
            }
        );
    }

    /**
     * Checksums + GA {@code maven-metadata.xml} regeneration (WS4-maven.4)
     * + {@link ArtifactEvent} recording for a primary that just became
     * servable at {@code key} — the tail shared by the plain hosted-write
     * path ({@link #saveAndRegenerate}, {@code verifyPgp} disabled) and by
     * H1's quarantine promotion once a staged primary's signature verifies
     * ({@link #verifyStagedPrimary}). The caller is responsible for the
     * primary already being at its real, servable {@code key} by the time
     * this runs.
     *
     * @param key Primary artifact key (already at its real location)
     * @param keyPath Same key as a path string (avoids re-deriving it)
     * @param owner Uploading user
     * @param size Artifact size, for the audit/event record
     * @return Completable future yielding the 201 Created response
     */
    private CompletableFuture<Response> publishPrimary(
        final Key key, final String keyPath, final String owner, final long size
    ) {
        final CompletableFuture<String> checksums = this.shouldGenerateChecksums(key)
            ? this.generateChecksums(key)
            : CompletableFuture.completedFuture(null);
        return checksums.thenCompose(
            sha256 -> this.regenerateMetadataIfPrimary(keyPath).thenApply(ignored -> sha256)
        ).thenCompose(
            sha256 -> this.addEvent(key, owner, size, sha256)
                .thenApply(ignored -> ResponseBuilder.created().build())
        );
    }

    /**
     * WS4-maven.4: regenerate the GA-level {@code maven-metadata.xml}
     * after a primary-artifact save, under a per-GA exclusive lock, so
     * concurrent/stale deploys converge on the true version set in
     * storage instead of last-write-wins client XML. A best-effort
     * operation: failures are logged, never surfaced to the client — the
     * artifact itself is already safely stored, and the next deploy (or
     * an admin re-trigger) retries the regeneration.
     *
     * @param keyPath Path just saved
     * @return Completion stage, always resolves (never fails)
     */
    private CompletionStage<Void> regenerateMetadataIfPrimary(final String keyPath) {
        final Optional<GavCoordinates> gav = GavCoordinates.parse(keyPath);
        if (gav.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        final GavCoordinates coords = gav.get();
        return new MavenMetadataRegenerator(this.storage)
            .regenerate(coords.baseKey(), coords.groupId(), coords.artifactId(), coords.version())
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.maven")
                    .message("maven-metadata.xml regeneration failed for "
                        + coords.baseKey().string() + "; a subsequent deploy will retry")
                    .eventCategory("file")
                    .eventAction("maven_metadata_regenerate")
                    .eventOutcome("failure")
                    .field("repository.name", this.rname)
                    .error(err)
                    .field("log.source", "application")
                    .log();
                return null;
            });
    }

    /**
     * WS4-maven.5: verify an uploaded checksum sidecar against the digest
     * of the already-stored primary. When the primary is absent (checksum
     * arrived first — not the normal Maven ordering, but tolerated),
     * verification is skipped and the sidecar is saved as-is. Reads the
     * uploaded bytes and the primary exactly once each.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> handleChecksumSidecarUpload(
        final Key key, final String keyPath, final Content body, final Headers headers,
        final String owner, final long size, final AuditContext auditCtx
    ) {
        final String algorithm = checksumAlgorithm(keyPath);
        final Key primaryKey = new Key.From(
            keyPath.substring(0, keyPath.length() - algorithm.length() - 1)
        );
        return new ContentWithSize(body, headers).asBytesFuture().thenCompose(
            uploadedBytes -> this.storage.exists(primaryKey).thenCompose(primaryExists -> {
                if (!primaryExists) {
                    return this.storage.save(key, new Content.From(uploadedBytes))
                        .thenApply(nothing -> ResponseBuilder.created().build());
                }
                return this.storage.value(primaryKey).thenCompose(
                    primary -> new ContentDigest(primary, Digests.valueOf(algorithm.toUpperCase(Locale.US))).hex()
                ).thenCompose(expectedHex -> {
                    final String uploadedHex = new String(uploadedBytes, StandardCharsets.UTF_8)
                        .trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
                    if (expectedHex.equalsIgnoreCase(uploadedHex)) {
                        return this.storage.save(key, new Content.From(uploadedBytes))
                            .thenApply(nothing -> ResponseBuilder.created().build());
                    }
                    return this.rejectChecksumMismatch(primaryKey, keyPath, owner, size, auditCtx);
                });
            })
        ).exceptionally(throwable -> {
            EcsLogger.error("com.auto1.pantera.maven")
                .message("Failed to verify/save checksum sidecar")
                .eventCategory("web")
                .eventAction("checksum_upload")
                .eventOutcome("failure")
                .error(throwable)
                .field("package.path", keyPath)
                .field("log.source", "application")
                .log();
            return ResponseBuilder.internalError().build();
        });
    }

    /**
     * Log + audit a checksum-mismatch rejection. Does not delete the
     * (already-verified, previously-stored) primary — only the *claimed*
     * checksum was wrong, so the bytes it describes are left exactly as
     * they were before this sidecar upload.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> rejectChecksumMismatch(
        final Key primaryKey, final String sidecarPath, final String owner, final long size,
        final AuditContext auditCtx
    ) {
        final GavCoordinates gav = GavCoordinates.parse(primaryKey.string()).orElse(null);
        EcsLogger.warn("com.auto1.pantera.maven")
            .message("Checksum verification failed for uploaded sidecar: " + sidecarPath)
            .eventCategory("file")
            .eventAction("checksum_verification_failed")
            .eventOutcome("failure")
            .field("repository.name", this.rname)
            .field("package.path", sidecarPath)
            .field("log.source", "application")
            .log();
        AuditLogger.publish(
            auditCtx, "maven", this.rname,
            gav != null ? gav.artifactName() : sidecarPath,
            gav != null ? gav.version() : null,
            size, owner, null, null,
            AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_CHECKSUM_MISMATCH
        );
        return CompletableFuture.completedFuture(ResponseBuilder.badRequest().build());
    }

    /**
     * WS4-maven.2 (hosted half) + H1 fix: verify a {@code .asc}/{@code .sig}
     * upload against the primary it signs.
     *
     * <ul>
     *   <li>The primary already lives at its real, servable key (published
     *       before {@code verifyPgp} was enabled, or this is a re-signature):
     *       verify directly against it — pre-H1 behaviour, unchanged. On
     *       failure the (already-public) primary and its checksum sidecars
     *       are deleted so nothing tampered/unsigned is left published.</li>
     *   <li>No real primary yet, but one is quarantined (H1's normal case —
     *       the primary's own PUT staged it first): verify against the
     *       staged bytes. VERIFIED promotes the staged primary to its real
     *       key (see {@link #verifyStagedPrimary}); anything else deletes
     *       the staged primary — it was never published, so there is
     *       nothing to "unpublish".</li>
     *   <li>Neither a real nor a staged primary exists (H1 bypass (a): the
     *       signature arrived first): the signature bytes are themselves
     *       quarantined so the primary's own upload can find and verify
     *       against them the moment it arrives (see
     *       {@link #stagePrimaryForVerification}) — verification never runs
     *       against absent bytes, and nothing is ever served unverified.</li>
     * </ul>
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> handleSignatureUpload(
        final Key key, final String keyPath, final Content body, final Headers headers,
        final String owner, final long size, final AuditContext auditCtx
    ) {
        final String suffix = keyPath.endsWith(".asc") ? ".asc" : ".sig";
        final Key primaryKey = new Key.From(keyPath.substring(0, keyPath.length() - suffix.length()));
        return new ContentWithSize(body, headers).asBytesFuture().thenCompose(
            sigBytes -> this.storage.exists(primaryKey).thenCompose(primaryExists -> {
                if (primaryExists) {
                    return this.verifyAgainstPublishedPrimary(
                        primaryKey, keyPath, key, sigBytes, owner, size, auditCtx
                    );
                }
                return this.verifyAgainstStagedOrDefer(
                    primaryKey, primaryKey.string(), key, sigBytes, owner, size, auditCtx
                );
            })
        ).exceptionally(throwable -> {
            EcsLogger.error("com.auto1.pantera.maven")
                .message("Failed to verify/save PGP signature")
                .eventCategory("web")
                .eventAction("pgp_verification_failed")
                .eventOutcome("failure")
                .error(throwable)
                .field("package.path", keyPath)
                .field("log.source", "application")
                .log();
            return ResponseBuilder.internalError().build();
        });
    }

    /**
     * Pre-H1 verification path: the primary is already real/servable
     * (published before {@code verifyPgp} was turned on for this repo, or
     * this is a re-signature of an already-published release). Verify
     * directly against it; unchanged from pre-2.3.2 behaviour.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> verifyAgainstPublishedPrimary(
        final Key primaryKey, final String signaturePath, final Key sigKey, final byte[] sigBytes,
        final String owner, final long size, final AuditContext auditCtx
    ) {
        return this.storage.value(primaryKey).thenCompose(Content::asBytesFuture).thenCompose(
            primaryBytes -> {
                final PgpVerifier.Result result = new PgpVerifier(KeyringStoreRegistry.active())
                    .verify(primaryBytes, sigBytes);
                if (result == PgpVerifier.Result.VERIFIED) {
                    return this.storage.save(sigKey, new Content.From(sigBytes))
                        .thenApply(nothing -> ResponseBuilder.created().build());
                }
                return this.rejectPgpVerification(primaryKey, signaturePath, result, owner, size, auditCtx);
            }
        );
    }

    /**
     * H1 fix: no real (servable) primary exists yet. If one is quarantined
     * — the ordinary case, since the primary's own PUT reaches storage
     * first in a standard {@code mvn deploy}/{@code gpg:sign-and-deploy}
     * flow — verify against the staged bytes now. Otherwise (bypass (a):
     * the signature arrived before even a staged primary exists) stage the
     * signature itself so the eventual primary upload can find and verify
     * against it — see {@link #stagePrimaryForVerification}.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> verifyAgainstStagedOrDefer(
        final Key primaryKey, final String primaryPath, final Key sigKey, final byte[] sigBytes,
        final String owner, final long size, final AuditContext auditCtx
    ) {
        return this.storage.exists(this.stagingKey(primaryKey)).thenCompose(staged -> {
            if (staged) {
                return this.storage.value(this.stagingKey(primaryKey)).thenCompose(Content::asBytesFuture)
                    .thenCompose(primaryBytes -> this.verifyStagedPrimary(
                        primaryKey, primaryPath, primaryBytes, sigBytes, owner, size, auditCtx,
                        () -> this.storage.save(sigKey, new Content.From(sigBytes))
                    ));
            }
            return this.storage.save(this.stagingKey(sigKey), new Content.From(sigBytes))
                .thenApply(nothing -> ResponseBuilder.created().build());
        });
    }

    /**
     * H1 fix: quarantine an uploaded primary instead of saving it to its
     * real (servable) key while {@code verifyPgp} is enabled for this repo.
     * A primary staged here is invisible to every read path — {@code
     * LocalMavenSlice} only ever looks at the real key, and {@link
     * MavenMetadataRegenerator}'s per-GA listing never descends into
     * {@link #STAGING_PREFIX} — so it cannot be served, and cannot appear
     * in {@code maven-metadata.xml}'s {@code <versions>}, until promoted.
     *
     * <p>If a signature was already staged for this exact primary (bypass
     * (a): the {@code .asc} arrived first), verifies immediately —
     * regardless of upload order, a matching verified signature is
     * required before anything becomes servable. Otherwise responds 201
     * (the client's PUT succeeds; nothing is servable yet) and waits for
     * {@link #handleSignatureUpload} to find this staged primary later.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> stagePrimaryForVerification(
        final Key key, final String keyPath, final Content body, final Headers headers,
        final String owner, final long size, final AuditContext auditCtx
    ) {
        final Key staged = this.stagingKey(key);
        return this.storage.save(staged, new ContentWithSize(body, headers)).thenCompose(
            nothing -> {
                EcsLogger.debug("com.auto1.pantera.maven")
                    .message("Staged primary artifact pending PGP signature: " + keyPath)
                    .eventCategory("file")
                    .eventAction("pgp_stage")
                    .field("repository.name", this.rname)
                    .field("package.path", keyPath)
                    .field("log.source", "application")
                    .log();
                return this.findStagedSignatureKey(key);
            }
        ).thenCompose(sigKeyOpt -> {
            if (sigKeyOpt.isEmpty()) {
                return CompletableFuture.completedFuture(ResponseBuilder.created().build());
            }
            final Key stagedSigKey = sigKeyOpt.get();
            return this.storage.value(staged).thenCompose(Content::asBytesFuture)
                .thenCompose(primaryBytes -> this.storage.value(stagedSigKey).thenCompose(Content::asBytesFuture)
                    .thenCompose(sigBytes -> this.verifyStagedPrimary(
                        key, keyPath, primaryBytes, sigBytes, owner, size, auditCtx,
                        () -> this.storage.move(stagedSigKey, unstagedKey(stagedSigKey))
                    )));
        }).exceptionally(throwable -> {
            EcsLogger.error("com.auto1.pantera.maven")
                .message("Failed to stage primary pending PGP verification")
                .eventCategory("web")
                .eventAction("pgp_verification_failed")
                .eventOutcome("failure")
                .error(throwable)
                .field("package.path", keyPath)
                .field("log.source", "application")
                .log();
            return ResponseBuilder.internalError().build();
        });
    }

    /**
     * The one place H1's staged/verify/promote-or-reject decision is made,
     * shared by both directions a staged primary can meet its signature:
     * the primary arriving after an already-staged signature ({@link
     * #stagePrimaryForVerification}) and the signature arriving after an
     * already-staged primary ({@link #verifyAgainstStagedOrDefer}).
     *
     * <p>VERIFIED promotes: moves the staged primary to its real key,
     * lands the signature at its real key via the caller-supplied {@code
     * landSignature} (a move-from-staging or a fresh save, depending on
     * which direction triggered this), then runs the same checksums +
     * metadata-regeneration + {@link ArtifactEvent} tail a non-quarantined
     * publish does ({@link #publishPrimary}) — so a PGP-verified hosted
     * publish is indistinguishable from a plain one once it lands. Any
     * other {@link PgpVerifier.Result} rejects: the staged primary (and any
     * staged signature) is deleted — nothing was ever published, so there
     * is nothing to unpublish, only quarantine to discard.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> verifyStagedPrimary(
        final Key primaryKey, final String primaryPath, final byte[] primaryBytes, final byte[] sigBytes,
        final String owner, final long size, final AuditContext auditCtx,
        final Supplier<CompletableFuture<Void>> landSignature
    ) {
        final PgpVerifier.Result result = new PgpVerifier(KeyringStoreRegistry.active())
            .verify(primaryBytes, sigBytes);
        if (result == PgpVerifier.Result.VERIFIED) {
            return this.storage.move(this.stagingKey(primaryKey), primaryKey)
                .thenCompose(nothing -> landSignature.get())
                .thenCompose(nothing -> this.publishPrimary(primaryKey, primaryPath, owner, size));
        }
        return this.rejectStagedPgp(primaryKey, primaryPath, result, owner, size, auditCtx);
    }

    /**
     * Roll back a quarantined publish that failed PGP verification: log the
     * {@code pgp_verification_failed} state transition, emit the {@code
     * artifact_publish} failure audit, and delete the staged primary plus
     * any staged signature — nothing real was ever created, so (unlike
     * {@link #rejectPgpVerification}) there is no published artifact to
     * unpublish.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> rejectStagedPgp(
        final Key primaryKey, final String primaryPath, final PgpVerifier.Result result,
        final String owner, final long size, final AuditContext auditCtx
    ) {
        final GavCoordinates gav = GavCoordinates.parse(primaryPath).orElse(null);
        EcsLogger.warn("com.auto1.pantera.maven")
            .message("PGP verification failed for staged hosted upload (" + result
                + "); " + primaryPath + " was never published")
            .eventCategory("file")
            .eventAction("pgp_verification_failed")
            .eventOutcome("failure")
            .field("repository.name", this.rname)
            .field("package.path", primaryPath)
            .field("event.reason", result.name())
            .field("log.source", "application")
            .log();
        AuditLogger.publish(
            auditCtx, "maven", this.rname,
            gav != null ? gav.artifactName() : primaryPath,
            gav != null ? gav.version() : null,
            size, owner, null, null,
            AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_CHECKSUM_MISMATCH
        );
        return this.deleteStagedArtifacts(primaryKey).thenApply(ignored -> ResponseBuilder.forbidden().build());
    }

    /**
     * Best-effort delete of a staged primary plus any staged {@code .asc}/
     * {@code .sig} counterpart — the quarantine-side analogue of {@link
     * #deleteWithChecksums}.
     * @param primaryKey Real (unstaged) primary key
     * @return Completion stage, always resolves
     */
    private CompletableFuture<Void> deleteStagedArtifacts(final Key primaryKey) {
        CompletableFuture<Void> chain = this.deleteIfExists(this.stagingKey(primaryKey));
        for (final String suffix : SIGNATURE_SUFFIXES) {
            final Key stagedSig = this.stagingKey(new Key.From(primaryKey.string() + suffix));
            chain = chain.thenCompose(ignored -> this.deleteIfExists(stagedSig));
        }
        return chain;
    }

    /**
     * @param key Key to delete if present
     * @return Completion stage, always resolves (best-effort — a delete
     *         failure is swallowed, same posture as {@link #deleteWithChecksums})
     */
    private CompletableFuture<Void> deleteIfExists(final Key key) {
        return this.storage.exists(key).thenCompose(
            exists -> exists
                ? this.storage.delete(key).exceptionally(ignored -> null)
                : CompletableFuture.<Void>completedFuture(null)
        );
    }

    /**
     * Look for a staged {@code .asc} or {@code .sig} counterpart of {@code
     * primaryKey} (in that preference order — {@code .asc} is the
     * conventional OpenPGP armored form).
     * @param primaryKey Real (unstaged) primary key
     * @return The staged signature key, if either suffix is staged
     */
    private CompletableFuture<Optional<Key>> findStagedSignatureKey(final Key primaryKey) {
        CompletableFuture<Optional<Key>> chain = CompletableFuture.completedFuture(Optional.empty());
        for (final String suffix : SIGNATURE_SUFFIXES) {
            final Key candidate = this.stagingKey(new Key.From(primaryKey.string() + suffix));
            chain = chain.thenCompose(
                found -> found.isPresent()
                    ? CompletableFuture.completedFuture(found)
                    : this.storage.exists(candidate).thenApply(
                        exists -> exists ? Optional.of(candidate) : Optional.<Key>empty()
                    )
            );
        }
        return chain;
    }

    /**
     * @param real Real (servable) key
     * @return The quarantine-namespaced key {@code real} is staged under
     *         while awaiting PGP verification
     */
    private Key stagingKey(final Key real) {
        return new Key.From(STAGING_PREFIX, real.string());
    }

    /**
     * Inverse of {@link #stagingKey}.
     * @param staged A key produced by {@link #stagingKey}
     * @return The real key {@code staged} quarantines
     */
    private static Key unstagedKey(final Key staged) {
        final String prefix = STAGING_PREFIX + "/";
        final String path = staged.string();
        return new Key.From(path.substring(prefix.length()));
    }

    /**
     * Roll back a hosted PGP-verification failure: delete the primary and
     * its checksum sidecars (best-effort), log the
     * {@code pgp_verification_failed} state transition, and emit the
     * {@code artifact_publish} failure audit.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> rejectPgpVerification(
        final Key primaryKey, final String signaturePath, final PgpVerifier.Result result,
        final String owner, final long size, final AuditContext auditCtx
    ) {
        final GavCoordinates gav = GavCoordinates.parse(primaryKey.string()).orElse(null);
        EcsLogger.warn("com.auto1.pantera.maven")
            .message("PGP verification failed for hosted upload (" + result
                + "); removing primary " + primaryKey.string())
            .eventCategory("file")
            .eventAction("pgp_verification_failed")
            .eventOutcome("failure")
            .field("repository.name", this.rname)
            .field("package.path", signaturePath)
            .field("event.reason", result.name())
            .field("log.source", "application")
            .log();
        AuditLogger.publish(
            auditCtx, "maven", this.rname,
            gav != null ? gav.artifactName() : signaturePath,
            gav != null ? gav.version() : null,
            size, owner, null, null,
            AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_CHECKSUM_MISMATCH
        );
        return this.deleteWithChecksums(primaryKey)
            .thenApply(ignored -> ResponseBuilder.forbidden().build());
    }

    /**
     * Best-effort delete of a primary plus its {@code .md5}/{@code .sha1}/
     * {@code .sha256}/{@code .sha512} sidecars.
     * @param primaryKey Primary artifact key
     * @return Completion stage, always resolves
     */
    private CompletableFuture<Void> deleteWithChecksums(final Key primaryKey) {
        CompletableFuture<Void> chain = this.storage.delete(primaryKey)
            .exceptionally(ignored -> null);
        for (final String ext : List.of(".md5", ".sha1", ".sha256", ".sha512")) {
            final Key sidecarKey = new Key.From(primaryKey.string() + ext);
            chain = chain.thenCompose(
                ignored -> this.storage.exists(sidecarKey).thenCompose(
                    exists -> exists
                        ? this.storage.delete(sidecarKey).exceptionally(ignored2 -> null)
                        : CompletableFuture.<Void>completedFuture(null)
                )
            );
        }
        return chain;
    }

    /**
     * @param path Upload path
     * @return True when this is the {@code maven-metadata.xml} content
     *         itself (not one of its checksum sidecars) — matches the
     *         original pre-2.3.0 routing exactly (only {@code .sha1}/
     *         {@code .md5} are excluded here; see {@link #isMetadataXmlChecksum}
     *         for the historical {@code .sha256}/{@code .sha512} nuance)
     */
    private static boolean isMetadataXmlContent(final String path) {
        return path.contains("maven-metadata.xml")
            && !path.endsWith(".sha1") && !path.endsWith(".md5");
    }

    /**
     * @param path Upload path
     * @return True for a {@code maven-metadata.xml.{md5,sha1,sha256,sha512}}
     *         upload. Reachable in practice only for {@code .sha1}/
     *         {@code .md5} — {@link #isMetadataXmlContent} already claims
     *         {@code .sha256}/{@code .sha512} metadata-checksum paths, a
     *         pre-existing quirk kept byte-identical (out of WS4-maven's
     *         scope to change).
     */
    private static boolean isMetadataXmlChecksum(final String path) {
        return path.contains("maven-metadata.xml")
            && (path.endsWith(".sha1") || path.endsWith(".md5")
                || path.endsWith(".sha256") || path.endsWith(".sha512"));
    }

    /**
     * @param path Upload path (not metadata.xml — callers check that first)
     * @return True for a {@code .md5}/{@code .sha1}/{@code .sha256}/
     *         {@code .sha512} checksum sidecar of a real primary artifact
     */
    private static boolean isChecksumSidecar(final String path) {
        return path.endsWith(".md5") || path.endsWith(".sha1")
            || path.endsWith(".sha256") || path.endsWith(".sha512");
    }

    /**
     * @param path Upload path
     * @return True for a {@code .asc}/{@code .sig} detached signature sidecar
     */
    private static boolean isSignatureSidecar(final String path) {
        return path.endsWith(".asc") || path.endsWith(".sig");
    }

    /**
     * @param checksumPath A path known to satisfy {@link #isChecksumSidecar}
     * @return The checksum algorithm token ({@code md5}/{@code sha1}/
     *         {@code sha256}/{@code sha512})
     */
    private static String checksumAlgorithm(final String checksumPath) {
        return checksumPath.substring(checksumPath.lastIndexOf('.') + 1);
    }

    /**
     * Capture request correlation (trace id / client IP) for audit
     * emission, mirroring {@code BaseCachedProxySlice#captureAuditContext}.
     * Must be called before any async hop — MDC does not survive worker-
     * thread hops (CLAUDE.md).
     * @param headers Inbound request headers
     * @return Captured context
     */
    private AuditContext captureAuditContext(final Headers headers) {
        RequestContextHeaders.bindToMdc(headers);
        return new AuditContext(MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP));
    }

    /**
     * Normalize maven-metadata.xml bytes.
     *
     * <p>Ensures {@code <latest>} is the highest version (adding it if absent) and
     * normalises {@code <lastUpdated>} to Maven-standard {@code yyyyMMddHHmmss} UTC.
     * Epoch-millisecond values written by older clients are corrected here.
     *
     * @param bytes Original metadata XML bytes
     * @return Completable future with normalised bytes
     */
    private CompletableFuture<byte[]> fixMetadataBytes(final byte[] bytes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                final String xml = new String(bytes, StandardCharsets.UTF_8);
                // Parse via our own DocumentBuilder with a silent ErrorHandler so
                // malformed XML (BOM, HTML error pages served as metadata) does
                // NOT spill "[Fatal Error] :1:1: ..." to stderr — the SAX default
                // handler prints before the exception propagates.
                final XMLDocument doc = new XMLDocument(parseSilently(xml));
                final List<String> versions = doc.xpath("//version/text()");
                if (versions.isEmpty()) {
                    return bytes;
                }

                final String highestVersion = versions.stream()
                    .max(Comparator.comparing(Version::new))
                    .orElse(versions.get(versions.size() - 1));

                final List<String> currentLatest = doc.xpath("//latest/text()");
                final String existingLatest = currentLatest.isEmpty() ? null : currentLatest.get(0);

                final String newLatest;
                if (existingLatest == null || existingLatest.isEmpty()) {
                    newLatest = highestVersion;
                } else {
                    final Version existing = new Version(existingLatest);
                    final Version highest = new Version(highestVersion);
                    newLatest = highest.compareTo(existing) > 0 ? highestVersion : existingLatest;
                }

                String result = xml;

                // Update existing <latest> or insert it before <release>/<versions>
                if (existingLatest != null && !existingLatest.isEmpty()) {
                    result = result.replaceFirst(
                        "<latest>[^<]*</latest>",
                        Matcher.quoteReplacement("<latest>" + newLatest + "</latest>")
                    );
                } else if (result.contains("<release>")) {
                    result = result.replaceFirst(
                        "<release>",
                        Matcher.quoteReplacement("<latest>" + newLatest + "</latest>\n    <release>")
                    );
                } else if (result.contains("<versions>")) {
                    result = result.replaceFirst(
                        "<versions>",
                        Matcher.quoteReplacement("<latest>" + newLatest + "</latest>\n    <versions>")
                    );
                }

                // Always normalise <lastUpdated> to yyyyMMddHHmmss UTC.
                // This repairs epoch-millisecond values from older clients/versions.
                final String timestamp = MavenTimestamp.now();
                if (result.contains("<lastUpdated>")) {
                    result = result.replaceFirst(
                        "<lastUpdated>[^<]*</lastUpdated>",
                        Matcher.quoteReplacement("<lastUpdated>" + timestamp + "</lastUpdated>")
                    );
                } else {
                    result = result.replaceFirst(
                        "</versioning>",
                        Matcher.quoteReplacement(
                            "    <lastUpdated>" + timestamp + "</lastUpdated>\n  </versioning>"
                        )
                    );
                }

                EcsLogger.debug("com.auto1.pantera.maven")
                    .message("Normalised maven-metadata.xml")
                    .eventCategory("web")
                    .eventAction("metadata_fix")
                    .eventOutcome("success")
                    .field("package.version", newLatest)
                    .field("log.source", "application")
                    .log();
                return result.getBytes(StandardCharsets.UTF_8);
            } catch (final IllegalArgumentException | SAXException | IOException
                           | ParserConfigurationException ex) {
                EcsLogger.warn("com.auto1.pantera.maven")
                    .message("Failed to parse metadata XML, using original")
                    .eventCategory("web")
                    .eventAction("metadata_fix")
                    .eventOutcome("failure")
                    .error(ex)
                    .field("log.source", "application")
                    .log();
                return bytes;
            }
        });
    }

    /**
     * Silent SAX error handler — lets the exception propagate so the caller
     * logs a structured WARN, but prevents the default handler from writing
     * {@code [Fatal Error] :1:1: Content is not allowed in prolog.} to stderr.
     */
    private static final ErrorHandler SILENT_SAX_HANDLER = new ErrorHandler() {
        @Override
        public void warning(final SAXParseException ex) { /* ignore */ }

        @Override
        public void error(final SAXParseException ex) throws SAXException {
            throw ex;
        }

        @Override
        public void fatalError(final SAXParseException ex) throws SAXException {
            throw ex;
        }
    };

    /**
     * Parse XML into a DOM document without printing SAX errors to stderr.
     * Disallows DOCTYPE declarations to defuse XXE and billion-laughs attacks.
     */
    private static Document parseSilently(final String xml)
        throws SAXException, IOException, ParserConfigurationException {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        final DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(SILENT_SAX_HANDLER);
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    /**
     * Check if we should generate checksums for this file.
     * Don't generate checksums for checksum files themselves.
     * @param key File key
     * @return True if checksums should be generated
     */
    private boolean shouldGenerateChecksums(final Key key) {
        final String path = key.string();
        return !path.endsWith(".md5") 
            && !path.endsWith(".sha1") 
            && !path.endsWith(".sha256") 
            && !path.endsWith(".sha512");
    }

    /**
     * Generate checksum sidecar files (MD5, SHA-1, SHA-256, SHA-512) for the
     * given artifact and return its SHA-256 hex digest.
     *
     * <p>The SHA-256 is surfaced explicitly so upload handlers can attach it
     * to the {@link ArtifactEvent} via {@link ArtifactEvent#withChecksum(String)}
     * — the audit log uses this for {@code package.checksum}.</p>
     *
     * @param key Original file key
     * @return Completable future yielding the SHA-256 hex digest
     */
    private CompletableFuture<String> generateChecksums(final Key key) {
        final List<CompletableFuture<String>> perAlg = CHECKSUM_ALGS.stream().map(
            alg -> this.storage.value(key).thenCompose(
                content -> new ContentDigest(
                    content, Digests.valueOf(alg.toUpperCase(Locale.US))
                ).hex()
            ).thenCompose(
                hex -> this.storage.save(
                    new Key.From(String.format("%s.%s", key.string(), alg)),
                    new Content.From(hex.getBytes(StandardCharsets.UTF_8))
                ).thenApply(ignored -> "sha256".equalsIgnoreCase(alg) ? hex : null)
            ).toCompletableFuture()
        ).toList();
        return CompletableFuture.allOf(perAlg.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> perAlg.stream()
                .map(CompletableFuture::join)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null));
    }

    /**
     * Add artifact event to queue for primary artifact uploads.
     *
     * <p>Uses structural filename-prefix detection — NOT an extension whitelist.
     * An upload qualifies as a primary artifact when:
     * <ul>
     *   <li>It lives under the Maven layout: {@code /{groupId}/{artifactId}/{version}/{filename}}</li>
     *   <li>The filename starts with {@code {artifactId}-} (Maven naming convention)</li>
     *   <li>It is NOT a companion file: metadata, checksum, signature, sources, or javadoc</li>
     * </ul>
     *
     * <p>This matches the invariant used by {@code ArtifactNameParser.parseMaven} on the
     * read path, keeping write- and read-side logic consistent. Any extension — {@code .yaml},
     * {@code .json}, {@code .zip}, future types — gets indexed as long as the filename follows
     * Maven naming.
     *
     * @param key Artifact key
     * @param owner Owner
     * @param size Artifact size
     */
    private CompletableFuture<Void> addEvent(
        final Key key, final String owner, final long size, final String sha256
    ) {
        final String path = key.string().startsWith("/") ? key.string() : "/" + key.string();

        if (!this.isPrimaryArtifactPath(path)) {
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Skipping non-primary artifact file for event")
                .eventCategory("web")
                .eventAction("event_creation")
                .field("package.path", path)
                .field("log.source", "application")
                .log();
            return CompletableFuture.completedFuture(null);
        }

        // pkg = "{groupId}/{artifactId}/{version}" (everything before the filename)
        final String pkg = path.substring(0, path.lastIndexOf('/'));
        return this.createAndAddEvent(pkg, owner, size, sha256);
    }

    /**
     * Check if a path represents a primary Maven artifact worth indexing.
     * See {@link #addEvent} for the full contract.
     *
     * @param path File path (always starts with '/')
     * @return True if this upload should produce an {@link ArtifactEvent}
     */
    private boolean isPrimaryArtifactPath(final String path) {
        if (this.isMetadataOrChecksum(path)) {
            return false;
        }
        if (path.endsWith(".asc") || path.endsWith(".sig")) {
            return false;
        }
        if (path.endsWith("-sources.jar") || path.endsWith("-javadoc.jar")) {
            return false;
        }
        final String[] segments = path.split("/");
        // Minimum: ["", groupId, artifactId, version, filename] = 5 segments
        if (segments.length < 5) {
            return false;
        }
        final String artifactId = segments[segments.length - 3];
        final String filename = segments[segments.length - 1];
        return filename.startsWith(artifactId + "-");
    }

    /**
     * Check if path is metadata or checksum file.
     * @param path File path
     * @return True if metadata or checksum
     */
    private boolean isMetadataOrChecksum(final String path) {
        return path.contains("maven-metadata.xml")
            || path.endsWith(".md5")
            || path.endsWith(".sha1")
            || path.endsWith(".sha256")
            || path.endsWith(".sha512");
    }

    /**
     * Create and add artifact event from package path.
     * @param pkg Package path (group/artifact/version)
     * @param owner Owner
     * @param size Artifact size
     */
    private CompletableFuture<Void> createAndAddEvent(
        final String pkg, final String owner, final long size, final String sha256
    ) {
        // Extract version (last directory before the file)
        final String[] parts = pkg.split("/");
        final String version = parts.length > 0 ? parts[parts.length - 1] : "unknown";

        // Remove version from pkg to get group/artifact only
        String groupArtifact = pkg.substring(0, pkg.lastIndexOf('/'));

        // Remove leading slash if present
        if (groupArtifact.startsWith("/")) {
            groupArtifact = groupArtifact.substring(1);
        }

        // Format artifact name as group.artifact (replacing / with .)
        final String artifactName = MavenSlice.EVENT_INFO.formatArtifactName(groupArtifact);

        // Drop any cached 404 for this artifact so a request that 404'd
        // before the upload (e.g. via a group fanout) does not keep
        // returning 404 once the artifact is live. Uses the URL-form
        // groupArtifact (slashes), matching what the proxy / group
        // slices write to the negative cache via NegativeCacheKey.fromPath.
        com.auto1.pantera.http.cache.NegativeCacheRegistry.instance()
            .invalidateAfterUpload("maven", groupArtifact);
        // Drop any cached cooldown-filtered envelope. The envelope cache
        // is keyed by the dotted artifactName (MavenSlice.EVENT_INFO
        // format) — same form the cooldown filter writes when caching
        // a filtered metadata.xml.
        com.auto1.pantera.cooldown.metadata.FilteredMetadataCacheRegistry.instance()
            .invalidateAfterUpload("maven", artifactName);

        final ArtifactEvent base = new ArtifactEvent(
            "maven",
            this.rname,
            owner == null || owner.isBlank() ? ArtifactEvent.DEF_OWNER : owner,
            artifactName,
            version,
            size,
            System.currentTimeMillis(),
            (Long) null  // No release date for uploads
        );
        final ArtifactEvent event = sha256 == null ? base : base.withChecksum(sha256);
        // Async path: queue for audit/metrics consumers (DbConsumer batches).
        this.events.ifPresent(queue -> queue.add(event));
        EcsLogger.debug("com.auto1.pantera.maven")
            .message("Added artifact event")
            .eventCategory("web")
            .eventAction("event_creation")
            .eventOutcome("success")
            .field("package.name", artifactName)
            .field("package.version", version)
            .field("package.size", size)
            .field("log.source", "application")
            .log();
        // Sync path: write the index row inline so the next group lookup
        // sees the artifact without waiting for the async batch.
        return this.syncIndex.recordSync(event);
    }

    /**
     * Record metric safely (only if metrics are enabled).
     * @param metric Metric recording action
     */
    private void recordMetric(final Runnable metric) {
        try {
            if (com.auto1.pantera.metrics.PanteraMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Failed to record metric")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }
}
