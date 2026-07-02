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
package com.auto1.pantera.audit;

import com.auto1.pantera.http.log.EcsLogger;

import java.util.List;

/**
 * Static helper class for structured artifact audit logging at INFO level.
 *
 * <p>All audit events are emitted to the {@code artifact.audit} logger and
 * routed through the dedicated log4j2 {@code AsyncConsole} appender.
 *
 * <p><b>Four events, each covering exactly one lifecycle moment for the
 * <i>actual binary artifact</i> — never pure-metadata requests (a
 * {@code maven-metadata.xml} GET, a PyPI simple-index render, an npm
 * packument fetch are never audited here; see {@link #resolution}, which
 * covers metadata listings on a separate, narrower basis).</b>
 *
 * <ul>
 *   <li>{@link #publish} — a local/hosted repo received a new artifact
 *       (upload). Never fires for a proxy cache-write; that is
 *       {@link #access}.</li>
 *   <li>{@link #access} — a client fetched (or was denied) the actual
 *       artifact bytes, from a proxy <i>or</i> a local repo, on a cache hit
 *       <i>or</i> a cache-miss-then-fetch. {@code outcome=failure} covers a
 *       cooldown block, a genuine not-found, or an upstream failure.</li>
 *   <li>{@link #delete} — an artifact (or artifact version) was removed.</li>
 *   <li>{@link #resolution} — a metadata/version listing was rendered for a
 *       proxy or group repo. Fires unconditionally (both when cooldown hid
 *       versions and when it did not) so every listing view has a
 *       corresponding audit record of who saw what.</li>
 * </ul>
 *
 * <p><b>No MDC fallback, by design.</b> Every identity field — repo type/name,
 * package name/version, owner, {@code trace.id}, {@code client.ip} — is an
 * explicit parameter. Earlier versions of this class read {@code client.ip}
 * from MDC and silently emitted nothing when the calling thread never had it
 * bound (Quartz workers, RxJava continuations, any adapter that forgot to
 * call {@code RequestContextHeaders.bindToMdc}). Making the fields
 * compulsory parameters converts that failure mode into a compile error.
 * {@link AuditContext} carries the two fields ({@code traceId}, {@code
 * clientIp}) that come from request-scoped correlation rather than the
 * artifact/action itself.
 *
 * <p>{@code repository.type}, {@code repository.name}, {@code package.name},
 * {@code package.version}, {@code user.name}, {@code client.ip} and
 * {@code trace.id} are all declared MDC-owned in {@link
 * com.auto1.pantera.http.log.EcsMdc}. Passing them via {@link
 * EcsLogger#field(String, Object)} is safe: {@link EcsLogger#log()} drops a
 * field value whenever {@code ThreadContext} already carries that key (the
 * synchronous request-thread case) and keeps it otherwise (the async-thread
 * case this class exists to fix) — so there is no duplicate-key risk either
 * way.
 *
 * @since 1.22.0
 */
public final class AuditLogger {

    private static final String LOGGER = "artifact.audit";

    /** {@code event.outcome} value for a successful action. */
    public static final String OUTCOME_SUCCESS = "success";

    /** {@code event.outcome} value for a failed/denied action. */
    public static final String OUTCOME_FAILURE = "failure";

    /** {@code event.reason} — upstream/local artifact bytes did not match the declared checksum. */
    public static final String REASON_CHECKSUM_MISMATCH = "checksum_mismatch";

    /** {@code event.reason} — the storage backend rejected or failed the write. */
    public static final String REASON_STORAGE_UNAVAILABLE = "storage_unavailable";

    /** {@code event.reason} — cooldown policy denied the fetch. */
    public static final String REASON_COOLDOWN_ACTIVE = "cooldown_active";

    /** {@code event.reason} — the artifact does not exist locally or upstream. */
    public static final String REASON_NOT_FOUND = "not_found";

    /** {@code event.reason} — the upstream remote could not be reached or errored. */
    public static final String REASON_UPSTREAM_UNAVAILABLE = "upstream_unavailable";

    /** {@code event.reason} — the caller is not authorized for this action. */
    public static final String REASON_FORBIDDEN = "forbidden";

    private AuditLogger() {
    }

    /**
     * Log an artifact publish (a local/hosted repo received a new artifact
     * via upload).
     *
     * <p>{@code size} is a byte count and is logged as an integer.
     * Earlier signatures took {@code double} which made the ECS JSON
     * layout emit scientific notation ({@code 3.64270308E8} for a
     * ~364 MB Docker layer) — unfriendly for both humans and any
     * downstream tooling that does numeric range queries on the field.
     *
     * @param ctx Request correlation context (trace id / client IP)
     * @param repoType Repository type
     * @param repoName Repository name
     * @param artifactName Artifact/package name
     * @param version Artifact version
     * @param size File size in bytes
     * @param owner Owner/uploader name
     * @param releaseDate Release timestamp epoch-millis, or {@code null} if absent
     * @param checksum SHA-256 hex digest, or {@code null} when unavailable
     * @param outcome {@link #OUTCOME_SUCCESS} or {@link #OUTCOME_FAILURE}
     * @param reason When {@code outcome} is {@link #OUTCOME_FAILURE}, one of the
     *               {@code REASON_*} constants; otherwise {@code null}
     */
    public static void publish(final AuditContext ctx, final String repoType, final String repoName,
        final String artifactName, final String version,
        final long size, final String owner, final Long releaseDate,
        final String checksum, final String outcome, final String reason) {
        final EcsLogger logger = EcsLogger.info(LOGGER)
            .message(publishMessage(outcome, releaseDate, reason))
            .eventCategory("file")
            .eventAction("artifact_publish")
            .eventOutcome(outcome)
            .field("log.source", "audit")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", artifactName)
            .field("package.version", version)
            .field("package.size", size)
            .field("user.name", owner)
            .field("client.ip", ctx.clientIp())
            .field("trace.id", ctx.traceId());
        if (checksum != null && !checksum.isEmpty()) {
            logger.field("package.checksum", checksum);
        }
        if (OUTCOME_FAILURE.equals(outcome) && reason != null && !reason.isEmpty()) {
            logger.field("event.reason", reason);
        }
        logger.log();
    }

    /**
     * Log an artifact access — a client fetched or was denied the actual
     * artifact bytes, whether the repo is proxy or local, and whether the
     * proxy path served from cache or fetched upstream.
     *
     * @param ctx Request correlation context (trace id / client IP)
     * @param repoType Repository type
     * @param repoName Repository name
     * @param artifactName Artifact/package name
     * @param version Artifact version, or {@code null}/empty when not resolvable
     * @param size File size in bytes, or {@code 0} when unknown (e.g. denied before headers)
     * @param owner Requesting user name
     * @param outcome {@link #OUTCOME_SUCCESS} or {@link #OUTCOME_FAILURE}
     * @param reason When {@code outcome} is {@link #OUTCOME_FAILURE}, one of the
     *               {@code REASON_*} constants; otherwise {@code null}
     */
    public static void access(final AuditContext ctx, final String repoType, final String repoName,
        final String artifactName, final String version, final long size,
        final String owner, final String outcome, final String reason) {
        final EcsLogger logger = EcsLogger.info(LOGGER)
            .message(accessMessage(outcome, reason))
            .eventCategory("file")
            .eventAction("artifact_access")
            .eventOutcome(outcome)
            .field("log.source", "audit")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", artifactName)
            .field("package.version", version)
            .field("package.size", size)
            .field("user.name", owner)
            .field("client.ip", ctx.clientIp())
            .field("trace.id", ctx.traceId());
        if (OUTCOME_FAILURE.equals(outcome) && reason != null && !reason.isEmpty()) {
            logger.field("event.reason", reason);
        }
        logger.log();
    }

    /**
     * Log an artifact delete.
     *
     * @param ctx Request correlation context (trace id / client IP)
     * @param repoType Repository type
     * @param repoName Repository name
     * @param artifactName Artifact/package name
     * @param version Artifact version, or {@code null}/empty for an all-versions delete
     * @param owner Requesting user name
     * @param outcome {@link #OUTCOME_SUCCESS} or {@link #OUTCOME_FAILURE}
     * @param reason When {@code outcome} is {@link #OUTCOME_FAILURE}, one of the
     *               {@code REASON_*} constants; otherwise {@code null}
     */
    public static void delete(final AuditContext ctx, final String repoType, final String repoName,
        final String artifactName, final String version, final String owner,
        final String outcome, final String reason) {
        final EcsLogger logger = EcsLogger.info(LOGGER)
            .message(deleteMessage(outcome, reason))
            .eventCategory("file")
            .eventAction("artifact_delete")
            .eventOutcome(outcome)
            .field("log.source", "audit")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", artifactName)
            .field("package.version", version)
            .field("user.name", owner)
            .field("client.ip", ctx.clientIp())
            .field("trace.id", ctx.traceId());
        if (OUTCOME_FAILURE.equals(outcome) && reason != null && !reason.isEmpty()) {
            logger.field("event.reason", reason);
        }
        logger.log();
    }

    /**
     * Log a metadata/version-listing view for a proxy or group repo.
     *
     * <p>Fires unconditionally — both when cooldown hid one or more versions
     * from the listing ({@code event.type=change}) and when it did not
     * ({@code event.type=allowed}) — so every listing view has an audit
     * record of who saw what versions. The filtered count and version list
     * are embedded in {@code message} rather than custom fields: {@code
     * cooldown.filtered_count} / {@code cooldown.filtered_versions} are not
     * ECS fields and would be dropped by a strict ECS ingest pipeline.
     *
     * @param ctx Request correlation context (trace id / client IP)
     * @param repoType Repository type
     * @param repoName Repository name
     * @param packageName Package/module name whose listing was rendered
     * @param owner Requesting user name
     * @param filteredVersions Versions hidden from the listing by cooldown;
     *                         empty when nothing was filtered
     */
    public static void resolution(final AuditContext ctx, final String repoType, final String repoName,
        final String packageName, final String owner, final List<String> filteredVersions) {
        final boolean changed = filteredVersions != null && !filteredVersions.isEmpty();
        EcsLogger.info(LOGGER)
            .message(resolutionMessage(filteredVersions))
            .eventCategory("file")
            .eventAction("artifact_resolution")
            .eventOutcome(OUTCOME_SUCCESS)
            .field("event.type", changed ? List.of("change") : List.of("allowed"))
            .field("log.source", "audit")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", packageName)
            .field("user.name", owner)
            .field("client.ip", ctx.clientIp())
            .field("trace.id", ctx.traceId())
            .log();
    }

    /**
     * Log a metadata/version-listing view whose cooldown-filter detail is
     * unknown for THIS serve — the listing was answered from a cache tier
     * (shared L2, HTTP 304 revalidation) that does not carry the
     * filtered-version list. The who/what/when of the request is still
     * recorded; {@code event.type} is {@code ["info"]} because neither
     * {@code allowed} nor {@code change} can be asserted.
     *
     * @param ctx Request correlation context (trace id / client IP)
     * @param repoType Repository type
     * @param repoName Repository name
     * @param packageName Package/module name whose listing was rendered
     * @param owner Requesting user name
     * @param detail Short serve-path note embedded in the message
     *               (e.g. {@code "shared cache"}, {@code "etag revalidation (304)"})
     */
    public static void resolutionDetailUnknown(final AuditContext ctx, final String repoType,
        final String repoName, final String packageName, final String owner, final String detail) {
        EcsLogger.info(LOGGER)
            .message(String.format(
                "Metadata listing served via %s; cooldown filter detail unavailable for this serve",
                detail
            ))
            .eventCategory("file")
            .eventAction("artifact_resolution")
            .eventOutcome(OUTCOME_SUCCESS)
            .field("event.type", List.of("info"))
            .field("log.source", "audit")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", packageName)
            .field("user.name", owner)
            .field("client.ip", ctx.clientIp())
            .field("trace.id", ctx.traceId())
            .log();
    }

    private static String publishMessage(final String outcome, final Long releaseDate, final String reason) {
        if (OUTCOME_FAILURE.equals(outcome)) {
            return "Artifact publish failed: " + reason;
        }
        return releaseDate != null
            ? String.format("Artifact publish recorded (release=%d)", releaseDate)
            : "Artifact publish recorded";
    }

    private static String accessMessage(final String outcome, final String reason) {
        if (OUTCOME_FAILURE.equals(outcome)) {
            return "Artifact access denied: " + reason;
        }
        return "Artifact access recorded";
    }

    private static String deleteMessage(final String outcome, final String reason) {
        if (OUTCOME_FAILURE.equals(outcome)) {
            return "Artifact delete failed: " + reason;
        }
        return "Artifact deleted";
    }

    private static String resolutionMessage(final List<String> filteredVersions) {
        if (filteredVersions == null || filteredVersions.isEmpty()) {
            return "Metadata listing served, no cooldown filtering applied";
        }
        return String.format(
            "Metadata listing served, %d version(s) filtered by cooldown: %s",
            filteredVersions.size(), String.join(", ", filteredVersions)
        );
    }
}
