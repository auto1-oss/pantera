/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer;

import com.artipie.ArtipieException;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Meta;
import com.artipie.asto.Storage;
import com.artipie.importer.DigestingContent.DigestResult;
import com.artipie.importer.api.ChecksumPolicy;
import com.artipie.importer.api.DigestType;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.ResponseException;
import com.artipie.http.log.EcsLogger;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.settings.repo.RepoConfig;
import com.artipie.settings.repo.Repositories;
import com.amihaiemil.eoyaml.YamlMapping;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service that orchestrates import persistence and metadata registration.
 *
 * @since 1.0
 */
public final class ImportService {

    /**
     * Supported digest algorithms for runtime computation.
     */
    private static final Set<DigestType> DEFAULT_DIGESTS = Set.of(
        DigestType.SHA1, DigestType.SHA256, DigestType.MD5
    );

    /**
     * Warn once when shards mode is enabled to remind merge triggering.
     */
    private static final AtomicBoolean MERGE_HINT_WARNED = new AtomicBoolean(false);

    /**
     * Repository registry.
     */
    private final Repositories repositories;

    /**
     * Session persistence.
     */
    private final Optional<ImportSessionStore> sessions;

    /**
     * Metadata event queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Enable metadata regeneration (default: false for backward compatibility).
     */
    private final boolean regenerateMetadata;

    /**
     * Ctor.
     *
     * @param repositories Repositories
     * @param sessions Session store
     * @param events Metadata queue
     */
    public ImportService(
        final Repositories repositories,
        final Optional<ImportSessionStore> sessions,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(repositories, sessions, events, false);
    }

    /**
     * @return true if repo type is Maven family (maven or gradle)
     */
    private static boolean isMavenFamily(final String type) {
        if (type == null) {
            return false;
        }
        final String t = type.toLowerCase(Locale.ROOT);
        return "maven".equals(t) || "gradle".equals(t);
    }

    

    /**
     * Ctor with metadata regeneration flag.
     *
     * @param repositories Repositories
     * @param sessions Session store
     * @param events Metadata queue
     * @param regenerateMetadata Enable metadata regeneration
     */
    public ImportService(
        final Repositories repositories,
        final Optional<ImportSessionStore> sessions,
        final Optional<Queue<ArtifactEvent>> events,
        final boolean regenerateMetadata
    ) {
        this.repositories = repositories;
        this.sessions = sessions;
        this.events = events;
        this.regenerateMetadata = regenerateMetadata;
    }

    /**
     * Normalize NPM artifact path to fix common import issues.
     * For scoped packages, ensures the tarball filename includes the scope prefix.
     * Example: @scope/package/-/package-1.0.0.tgz -> @scope/package/-/@scope/package-1.0.0.tgz
     *
     * @param repoType Repository type
     * @param path Original path
     * @return Normalized path
     */
    private static String normalizeNpmPath(final String repoType, final String path) {
        if (!"npm".equalsIgnoreCase(repoType)) {
            return path;
        }
        // Check if this is a scoped package tarball
        // Pattern: @scope/package/-/package-version.tgz (missing scope in tarball)
        if (!path.contains("@") || !path.contains("/-/")) {
            return path;
        }
        final String[] parts = path.split("/-/");
        if (parts.length != 2) {
            return path;
        }
        final String packagePrefix = parts[0]; // e.g., "@scope/package"
        final String tarballPath = parts[1];   // e.g., "package-version.tgz" or "@scope/package-version.tgz"
        
        // Extract scope from package prefix
        if (!packagePrefix.startsWith("@")) {
            return path;
        }
        final int slashIdx = packagePrefix.indexOf('/');
        if (slashIdx < 0) {
            return path;
        }
        final String scope = packagePrefix.substring(0, slashIdx + 1); // "@scope/"
        final String packageName = packagePrefix.substring(slashIdx + 1); // "package"
        
        // Check if tarball already has the scope prefix
        if (tarballPath.startsWith(scope)) {
            // Check if it's a duplicate (starts with scope + scope + package)
            final String duplicatePattern = scope + scope + packageName;
            if (tarballPath.startsWith(duplicatePattern)) {
                // Remove the duplicate scope
                final String correctedTarball = scope + tarballPath.substring(duplicatePattern.length());
                final String normalized = packagePrefix + "/-/" + correctedTarball;
                EcsLogger.debug("com.artipie.importer")
                    .message("Normalized NPM path (removed duplicate)")
                    .eventCategory("repository")
                    .eventAction("import_normalize")
                    .field("url.original", path)
                    .field("url.path", normalized)
                    .log();
                return normalized;
            }
            return path; // Already correct
        } else {
            // Tarball is missing scope prefix, add it
            final String correctedTarball = scope + tarballPath;
            final String normalized = packagePrefix + "/-/" + correctedTarball;
            EcsLogger.debug("com.artipie.importer")
                .message("Normalized NPM path (added scope)")
                .eventCategory("repository")
                .eventAction("import_normalize")
                .field("url.original", path)
                .field("url.path", normalized)
                .log();
            return normalized;
        }
    }

    /**
     * Sanitize Composer path by replacing spaces with + for web server compatibility.
     * Spaces in URLs cause "Malformed input to URL function" errors in cURL.
     * 
     * @param repoType Repository type
     * @param path Original path
     * @return Sanitized path with spaces replaced by +
     */
    private static String sanitizeComposerPath(final String repoType, final String path) {
        if (!"php".equalsIgnoreCase(repoType) && !"composer".equalsIgnoreCase(repoType)) {
            return path;
        }
        // Replace all spaces with + to avoid web server URL parsing issues
        // This makes the storage path match the URL without need for encoding
        return path.replaceAll("\\s+", "+");
    }

    /**
     * Import artifact.
     *
     * @param request Request metadata
     * @param content Body content
     * @return Import result
     */
    public CompletionStage<ImportResult> importArtifact(final ImportRequest request, final Content content) {
        final RepoConfig config = this.repositories.config(request.repo())
            .orElseThrow(() -> new ResponseException(
                ResponseBuilder.notFound()
                    .textBody(String.format("Repository '%s' not found", request.repo()))
                    .build()
            ));
        final Storage storage = config.storageOpt()
            .orElseThrow(() -> new ResponseException(
                ResponseBuilder.internalError()
                    .textBody("Repository storage is not configured")
                    .build()
            ));
        final Optional<String> baseUrl = repositoryBaseUrl(config);

        final ImportSession session = this.sessions
            .map(store -> store.start(request))
            .orElseGet(() -> ImportSession.transientSession(request));

        if (session.status() == ImportSessionStatus.COMPLETED
            || session.status() == ImportSessionStatus.SKIPPED) {
            EcsLogger.debug("com.artipie.importer")
                .message("Import skipped (already completed, session: " + session.key() + ")")
                .eventCategory("repository")
                .eventAction("import_artifact")
                .eventOutcome("skipped")
                .log();
            return CompletableFuture.completedFuture(
                new ImportResult(
                    ImportStatus.ALREADY_PRESENT,
                    "Artifact already imported",
                    buildPersistedDigests(session),
                    session.size().orElse(0L),
                    null
                )
            );
        }

        if (request.metadataOnly()) {
            EcsLogger.debug("com.artipie.importer")
                .message("Metadata-only import")
                .eventCategory("repository")
                .eventAction("import_artifact")
                .field("repository.name", request.repo())
                .field("url.path", request.path())
                .log();
            final long size = request.size().orElse(0L);
            this.sessions.ifPresent(store -> store.markCompleted(session, size, buildExpectedDigests(request)));
            this.enqueueEvent(request, size);
            return CompletableFuture.completedFuture(
                new ImportResult(
                    ImportStatus.CREATED,
                    "Metadata recorded",
                    buildExpectedDigests(request),
                    size,
                    null
                )
            );
        }

        // Normalize path for NPM to fix common import issues
        // Sanitize path for Composer to replace spaces with + (web server safe)
        String normalizedPath = normalizeNpmPath(request.repoType(), request.path());
        normalizedPath = sanitizeComposerPath(request.repoType(), normalizedPath);
        final Key target = new Key.From(normalizedPath);
        final Key staging = stagingKey(session);
        final Key quarantine = quarantineKey(session);

        final DigestingContent digesting;
        final Content payload;
        final String rtype = request.repoType() == null ? "" : request.repoType().toLowerCase(Locale.ROOT);
        final boolean needsDigests = isMavenFamily(rtype);
        if (request.policy() == ChecksumPolicy.COMPUTE || needsDigests) {
            digesting = new DigestingContent(content, DEFAULT_DIGESTS);
            payload = digesting;
        } else {
            digesting = null;
            payload = content;
        }

        return storage.exclusively(
            target,
            st -> st.save(staging, payload)
                .thenCompose(
                    ignored -> (digesting != null
                        ? digesting.result()
                        : resolveSize(st, staging, request.size()))
                )
                .thenCompose(
                    result -> finalizeImport(
                        request, session, st, staging, target, quarantine, result, baseUrl, config
                    )
                )
                // Configurable import timeout (default: 30 minutes)
                .orTimeout(Long.getLong("artipie.import.timeout.seconds", 1800L), TimeUnit.SECONDS)
        ).toCompletableFuture().exceptionally(err -> {
            EcsLogger.error("com.artipie.importer")
                .message("Import failed")
                .eventCategory("repository")
                .eventAction("import_artifact")
                .eventOutcome("failure")
                .field("repository.name", request.repo())
                .field("url.path", request.path())
                .error(err)
                .log();
            this.sessions.ifPresent(store -> store.markFailed(session, err.getMessage()));
            throw new CompletionException(err);
        });
    }

    /**
     * Finalize import by verifying, moving and recording metadata.
     *
     * @param request Request
     * @param session Session
     * @param storage Storage
     * @param staging Staging key
     * @param target Target key
     * @param quarantine Quarantine key
     * @param result Digest result
     * @return Completion stage with result
     */
    private CompletionStage<ImportResult> finalizeImport(
        final ImportRequest request,
        final ImportSession session,
        final Storage storage,
        final Key staging,
        final Key target,
        final Key quarantine,
        final DigestResult result,
        final Optional<String> baseUrl,
        final RepoConfig config
    ) {
        final long size = result.size();
        final EnumMap<DigestType, String> computed = new EnumMap<>(DigestType.class);
        computed.putAll(result.digests());
        final EnumMap<DigestType, String> expected = buildExpectedDigests(request);
        final EnumMap<DigestType, String> toPersist = new EnumMap<>(DigestType.class);
        toPersist.putAll(computed);
        expected.forEach(toPersist::putIfAbsent);

        final Optional<String> mismatch = validate(request, size, computed, expected);
        if (mismatch.isPresent()) {
            EcsLogger.warn("com.artipie.importer")
                .message("Checksum mismatch")
                .eventCategory("repository")
                .eventAction("import_artifact")
                .eventOutcome("failure")
                .field("repository.name", request.repo())
                .field("url.path", request.path())
                .field("error.message", mismatch.get())
                .log();
            final Storage root = rootStorage(storage).orElse(storage);
            if (root == storage) {
                // Same storage, simple move
                return storage.move(staging, quarantine).thenApply(
                    ignored -> {
                        this.sessions.ifPresent(
                            store -> store.markQuarantined(
                                session, size, toPersist, mismatch.get(), quarantine.string()
                            )
                        );
                        return new ImportResult(
                            ImportStatus.CHECKSUM_MISMATCH,
                            mismatch.get(),
                            toPersist,
                            size,
                            quarantine.string()
                        );
                    }
                );
            }
            // Different storages: copy to root quarantine, then delete staging
            return storage.value(staging)
                .thenCompose(content -> root.save(quarantine, content)
                    .thenCompose(v -> storage.delete(staging))
                )
                .thenApply(ignored -> {
                    this.sessions.ifPresent(
                        store -> store.markQuarantined(
                            session, size, toPersist, mismatch.get(), quarantine.string()
                        )
                    );
                    return new ImportResult(
                        ImportStatus.CHECKSUM_MISMATCH,
                        mismatch.get(),
                        toPersist,
                        size,
                        quarantine.string()
                    );
                });
        }

        return storage.move(staging, target)
            .whenComplete((ignored, moveErr) -> {
                // Always attempt cleanup regardless of move success/failure
                cleanupStagingDir(storage, staging)
                    .exceptionally(cleanupErr -> {
                        EcsLogger.debug("com.artipie.importer")
                            .message("Post-import cleanup error (non-critical)")
                            .eventCategory("repository")
                            .eventAction("import_cleanup")
                            .eventOutcome("failure")
                            .field("error.message", cleanupErr.getMessage())
                            .log();
                        return null;
                    });
            })
            .thenCompose(
                ignored -> {
                    // If metadata regeneration is enabled, we either write shards (merge mode)
                    // or perform direct regeneration (legacy mode).
                    if (this.regenerateMetadata) {
                        final boolean shards = shardsModeEnabled(config);
                        final String type = request.repoType();
                        if (shards && isShardEligible(type)) {
                            if (MERGE_HINT_WARNED.compareAndSet(false, true)) {
                                EcsLogger.warn("com.artipie.importer")
                                    .message("Import shard mode enabled: remember to trigger metadata merge via POST /.merge/{repo} after imports")
                                    .eventCategory("repository")
                                    .eventAction("import_artifact")
                                    .field("repository.name", request.repo())
                                    .log();
                            }
                            return writeShardsForImport(storage, target, request, size, toPersist, baseUrl)
                                .exceptionally(err -> {
                                    EcsLogger.warn("com.artipie.importer")
                                        .message("Shard write failed for repository '" + request.repo() + "' at key: " + target.string())
                                        .eventCategory("repository")
                                        .eventAction("import_shard_write")
                                        .eventOutcome("failure")
                                        .field("repository.name", request.repo())
                                        .field("error.message", err.getMessage())
                                        .log();
                                    return null;
                                })
                                .thenCompose(nothing -> isMavenFamily(type.toLowerCase(Locale.ROOT))
                                    ? writeSidecarChecksums(storage, target, toPersist)
                                    : CompletableFuture.completedFuture(null))
                                .thenApply(nothing -> {
                                    this.sessions.ifPresent(store -> store.markCompleted(session, size, toPersist));
                                    this.enqueueEvent(request, size);
                                    return new ImportResult(
                                        ImportStatus.CREATED,
                                        "Artifact imported (metadata shards queued)",
                                        toPersist,
                                        size,
                                        null
                                    );
                                });
                        } else {
                            final MetadataRegenerator regenerator = new MetadataRegenerator(
                                storage, type, request.repo(), baseUrl
                            );
                            return regenerator.regenerate(target, request)
                                .exceptionally(err -> {
                                    EcsLogger.warn("com.artipie.importer")
                                        .message("Metadata regeneration failed for repository '" + request.repo() + "' at key: " + target.string())
                                        .eventCategory("repository")
                                        .eventAction("import_metadata_regenerate")
                                        .eventOutcome("failure")
                                        .field("repository.name", request.repo())
                                        .field("error.message", err.getMessage())
                                        .log();
                                    return null; // Continue even if metadata regeneration fails
                                })
                                .thenCompose(nothing -> isMavenFamily(type.toLowerCase(Locale.ROOT))
                                    ? writeSidecarChecksums(storage, target, toPersist)
                                    : CompletableFuture.completedFuture(null))
                                .thenApply(nothing -> {
                                    this.sessions.ifPresent(store -> store.markCompleted(session, size, toPersist));
                                    this.enqueueEvent(request, size);
                                    return new ImportResult(
                                        ImportStatus.CREATED,
                                        "Artifact imported",
                                        toPersist,
                                        size,
                                        null
                                    );
                                });
                        }
                    } else {
                        this.sessions.ifPresent(store -> store.markCompleted(session, size, toPersist));
                        this.enqueueEvent(request, size);
                        return CompletableFuture.completedFuture(
                            new ImportResult(
                                ImportStatus.CREATED,
                                "Artifact imported",
                                toPersist,
                                size,
                                null
                            )
                        );
                    }
                }
            );
    }

    /**
     * Write a PyPI metadata shard for a single artifact under:
     * .meta/pypi/shards/{package}/{version}/{filename}.json
     */
    private static CompletionStage<Void> writePyPiShard(
        final Storage storage,
        final Key target,
        final ImportRequest request,
        final long size,
        final EnumMap<DigestType, String> digests
    ) {
        final String path = target.string();
        final String[] segs = path.split("/");
        if (segs.length < 3) {
            return CompletableFuture.completedFuture(null);
        }
        final String pkg = segs[0];
        final String ver = segs[1];
        final String file = segs[segs.length - 1];
        final String sha256 = digests.getOrDefault(DigestType.SHA256, null);
        // Build shard JSON with minimal fields needed to render simple index
        final String shard = String.format(
            Locale.ROOT,
            "{\"package\":\"%s\",\"version\":\"%s\",\"filename\":\"%s\",\"path\":\"%s\",\"size\":%d,\"sha256\":%s,\"ts\":%d}",
            escapeJson(pkg),
            escapeJson(ver),
            escapeJson(file),
            escapeJson(path),
            Long.valueOf(size),
            sha256 == null ? "null" : ("\"" + sha256 + "\""),
            System.currentTimeMillis()
        );
        final Key shardKey = new Key.From(".meta", "pypi", "shards", pkg, ver, file + ".json");
        return storage.save(shardKey, new Content.From(shard.getBytes(StandardCharsets.UTF_8)))
            .thenRun(() -> EcsLogger.info("com.artipie.importer")
                .message("Shard written [pypi]")
                .eventCategory("repository")
                .eventAction("import_shard_write")
                .eventOutcome("success")
                .field("package.name", pkg)
                .field("package.version", ver)
                .log());
    }

    /**
     * Validate digests and size.
     *
     * @param request Request
     * @param size Actual size
     * @param computed Computed digests
     * @param expected Expected digests
     * @return Optional mismatch description
     */
    private static Optional<String> validate(
        final ImportRequest request,
        final long size,
        final Map<DigestType, String> computed,
        final Map<DigestType, String> expected
    ) {
        if (request.size().isPresent() && request.size().get() != size) {
            return Optional.of(
                String.format(
                    "Size mismatch: expected %d bytes, got %d bytes",
                    request.size().get(),
                    size
                )
            );
        }
        if (request.policy() == ChecksumPolicy.COMPUTE) {
            for (final Map.Entry<DigestType, String> entry : expected.entrySet()) {
                final String cmp = computed.get(entry.getKey());
                if (cmp == null) {
                    return Optional.of(
                        String.format("Missing computed %s digest", entry.getKey())
                    );
                }
                if (!cmp.equalsIgnoreCase(entry.getValue())) {
                    return Optional.of(
                        String.format(
                            "%s digest mismatch: expected %s, got %s",
                            entry.getKey(),
                            entry.getValue(),
                            cmp
                        )
                    );
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Resolve digest result when runtime calculation skipped.
     *
     * @param storage Storage
     * @param staging Staging key
     * @param providedSize Provided size
     * @return Digest result with size and empty digests
     */
    private static CompletionStage<DigestResult> resolveSize(
        final Storage storage,
        final Key staging,
        final Optional<Long> providedSize
    ) {
        if (providedSize.isPresent()) {
            return CompletableFuture.completedFuture(new DigestResult(providedSize.get(), Map.of()));
        }
        return storage.metadata(staging).thenApply(
            meta -> meta.read(Meta.OP_SIZE)
                .orElseThrow(() -> new ArtipieException("Unable to determine uploaded size"))
        ).thenApply(size -> new DigestResult(size, Map.of()));
    }

    /**
     * Build digest map from request headers.
     *
     * @param request Request
     * @return Digest map
     */
    private static EnumMap<DigestType, String> buildExpectedDigests(final ImportRequest request) {
        final EnumMap<DigestType, String> digests = new EnumMap<>(DigestType.class);
        request.sha1().ifPresent(val -> digests.put(DigestType.SHA1, normalizeHex(val)));
        request.sha256().ifPresent(val -> digests.put(DigestType.SHA256, normalizeHex(val)));
        request.md5().ifPresent(val -> digests.put(DigestType.MD5, normalizeHex(val)));
        return digests;
    }

    /**
     * Build digest map from completed session.
     *
     * @param session Session
     * @return Digests map
     */
    private static EnumMap<DigestType, String> buildPersistedDigests(final ImportSession session) {
        final EnumMap<DigestType, String> digests = new EnumMap<>(DigestType.class);
        session.sha1().ifPresent(val -> digests.put(DigestType.SHA1, normalizeHex(val)));
        session.sha256().ifPresent(val -> digests.put(DigestType.SHA256, normalizeHex(val)));
        session.md5().ifPresent(val -> digests.put(DigestType.MD5, normalizeHex(val)));
        return digests;
    }

    /**
     * Normalize hex strings to lowercase.
     *
     * @param value Hex value
     * @return Normalized hex
     */
    private static String normalizeHex(final String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Cleanup staging directory after successful import.
     * Attempts to delete the staging file's parent directory if empty.
     *
     * @param storage Storage
     * @param staging Staging key
     * @return Completion stage
     */
    private static CompletionStage<Void> cleanupStagingDir(final Storage storage, final Key staging) {
        // Try to delete parent directory (.import/staging/session-id)
        // This will only succeed if the directory is empty
        final Key parent = staging.parent().orElse(null);
        if (parent != null && parent.string().startsWith(".import/staging/")) {
            return storage.delete(parent)
                .exceptionally(err -> {
                    // Ignore errors - directory might not be empty or already deleted
                    EcsLogger.debug("com.artipie.importer")
                        .message("Could not cleanup staging directory at key: " + parent.string())
                        .eventCategory("repository")
                        .eventAction("import_cleanup")
                        .field("error.message", err.getMessage())
                        .log();
                    return null;
                })
                .thenCompose(nothing -> {
                    // Also try to cleanup .import/staging if empty
                    final Key stagingParent = parent.parent().orElse(null);
                    if (stagingParent != null && ".import/staging".equals(stagingParent.string())) {
                        return storage.delete(stagingParent)
                            .exceptionally(err -> {
                                EcsLogger.debug("com.artipie.importer")
                                    .message("Could not cleanup .import/staging")
                                    .eventCategory("repository")
                                    .eventAction("import_cleanup")
                                    .field("error.message", err.getMessage())
                                    .log();
                                return null;
                            })
                            .thenCompose(nothing2 -> {
                                // Finally try to cleanup .import if empty
                                final Key importParent = stagingParent.parent().orElse(null);
                                if (importParent != null && ".import".equals(importParent.string())) {
                                    return storage.delete(importParent)
                                        .exceptionally(err -> {
                                            EcsLogger.debug("com.artipie.importer")
                                                .message("Could not cleanup .import")
                                                .eventCategory("repository")
                                                .eventAction("import_cleanup")
                                                .field("error.message", err.getMessage())
                                                .log();
                                            return null;
                                        });
                                }
                                return CompletableFuture.completedFuture(null);
                            });
                    }
                    return CompletableFuture.completedFuture(null);
                });
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Generate staging key.
     *
     * @param session Session
     * @return Staging key
     */
    private static Key stagingKey(final ImportSession session) {
        return new Key.From(".import", "staging", sanitize(session.key(), session.attempts()));
    }

    /**
     * Generate quarantine key.
     *
     * @param session Session
     * @return Quarantine key
     */
    private static Key quarantineKey(final ImportSession session) {
        return new Key.From(".import", "quarantine", sanitize(session.key(), session.attempts()));
    }

    /**
     * Obtain root storage if {@code storage} is a SubStorage; otherwise empty.
     * Uses reflection to access origin field to avoid changing public API.
     *
     * @param storage Storage instance
     * @return Optional root storage
     */
    private static Optional<Storage> rootStorage(final Storage storage) {
        try {
            final Class<?> sub = Class.forName("com.artipie.asto.SubStorage");
            if (sub.isInstance(storage)) {
                final java.lang.reflect.Field origin = sub.getDeclaredField("origin");
                origin.setAccessible(true);
                return Optional.of((Storage) origin.get(storage));
            }
        } catch (final Exception ignore) {
            // ignore and treat as not a SubStorage
        }
        return Optional.empty();
    }

    /**
     * Check if shards merge mode is enabled for this repository.
     * Reads repo setting `metadata_merge_mode` and returns true if set to `shards`.
     * Falls back to system property `artipie.metadata.merge.mode`.
     *
     * @param config Repo configuration
     * @return True when shard merge mode is enabled
     */
    private static boolean shardsModeEnabled(final RepoConfig config) {
        try {
            final Optional<YamlMapping> ym = config.settings();
            if (ym.isPresent()) {
                final String raw = ym.get().string("metadata_merge_mode");
                if (raw != null && !raw.isBlank()) {
                    final String mode = raw.trim().toLowerCase(Locale.ROOT);
                    // Explicit legacy/direct/off disables shards
                    if ("legacy".equals(mode) || "direct".equals(mode) || "off".equals(mode)) {
                        return false;
                    }
                    // Explicit shards enables shards
                    if ("shards".equals(mode)) {
                        return true;
                    }
                    // Unknown values: default to shards
                    return true;
                }
            }
        } catch (final Exception ex) {
            EcsLogger.debug("com.artipie.importer")
                .message("Could not read metadata_merge_mode from settings")
                .eventCategory("configuration")
                .eventAction("settings_read")
                .field("error.message", ex.getMessage())
                .log();
        }
        final String prop = System.getProperty("artipie.metadata.merge.mode", "");
        if (!prop.isBlank()) {
            final String mode = prop.trim().toLowerCase(Locale.ROOT);
            if ("legacy".equals(mode) || "direct".equals(mode) || "off".equals(mode)) {
                return false;
            }
            if ("shards".equals(mode)) {
                return true;
            }
        }
        // Default: shards enabled for imports
        return true;
    }

    /**
     * Write sidecar checksum files for the imported artifact when digests are available.
     */
    private static CompletionStage<Void> writeSidecarChecksums(
        final Storage storage,
        final Key target,
        final EnumMap<DigestType, String> digests
    ) {
        final java.util.List<CompletionStage<Void>> saves = new java.util.ArrayList<>(3);
        final String base = target.string();
        final String sha1 = digests.get(DigestType.SHA1);
        if (sha1 != null && !sha1.isBlank()) {
            saves.add(storage.save(new Key.From(base + ".sha1"),
                new Content.From(sha1.trim().getBytes(StandardCharsets.US_ASCII))));
        }
        final String md5 = digests.get(DigestType.MD5);
        if (md5 != null && !md5.isBlank()) {
            saves.add(storage.save(new Key.From(base + ".md5"),
                new Content.From(md5.trim().getBytes(StandardCharsets.US_ASCII))));
        }
        final String sha256 = digests.get(DigestType.SHA256);
        if (sha256 != null && !sha256.isBlank()) {
            saves.add(storage.save(new Key.From(base + ".sha256"),
                new Content.From(sha256.trim().getBytes(StandardCharsets.US_ASCII))));
        }
        if (saves.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(saves.stream().map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new));
    }

    /**
     * Whether repo type supports shard writing in this phase.
     *
     * @param repoType Repository type
     * @return True if shards should be written instead of direct metadata
     */
    private static boolean isShardEligible(final String repoType) {
        if (repoType == null) {
            return false;
        }
        final String t = repoType.toLowerCase(Locale.ROOT);
        return "maven".equals(t) || "gradle".equals(t) || "helm".equals(t) || "pypi".equals(t) || "python".equals(t);
    }

    /**
     * Write appropriate metadata shard(s) for supported repo types.
     *
     * @param storage Storage
     * @param target Target key
     * @param request Import request
     * @param size Size of artifact
     * @param digests Digests to persist
     * @param baseUrl Optional base URL
     * @return Completion stage
     */
    private CompletionStage<Void> writeShardsForImport(
        final Storage storage,
        final Key target,
        final ImportRequest request,
        final long size,
        final EnumMap<DigestType, String> digests,
        final Optional<String> baseUrl
    ) {
        final String type = request.repoType().toLowerCase(Locale.ROOT);
        if ("maven".equals(type) || "gradle".equals(type)) {
            return writeMavenShard(storage, target, request, size, digests);
        }
        if ("helm".equals(type)) {
            return writeHelmShard(storage, target, request, size, digests, baseUrl);
        }
        if ("pypi".equals(type) || "python".equals(type)) {
            return writePyPiShard(storage, target, request, size, digests);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Write a Maven/Gradle metadata shard for a single version under:
     * .meta/maven/shards/{groupPath}/{artifactId}/{version}/{filename}.json
     */
    private static CompletionStage<Void> writeMavenShard(
        final Storage storage,
        final Key target,
        final ImportRequest request,
        final long size,
        final EnumMap<DigestType, String> digests
    ) {
        final String path = target.string();
        // Skip maven-metadata.xml files - they should not be imported as artifacts
        if (path.contains("maven-metadata.xml")) {
            EcsLogger.debug("com.artipie.importer")
                .message("Skipping maven-metadata.xml file at path: " + path)
                .eventCategory("repository")
                .eventAction("import_shard_write")
                .log();
            return CompletableFuture.completedFuture(null);
        }
        final MavenCoords coords = inferMavenCoords(path, request.artifact().orElse(null), request.version().orElse(null));
        if (coords == null) {
            EcsLogger.debug("com.artipie.importer")
                .message("Could not infer Maven coords from path: " + path)
                .eventCategory("repository")
                .eventAction("import_shard_write")
                .field("package.name", request.artifact().orElse(null))
                .field("package.version", request.version().orElse(null))
                .log();
            return CompletableFuture.completedFuture(null);
        }
        EcsLogger.debug("com.artipie.importer")
            .message("Inferred Maven coords")
            .eventCategory("repository")
            .eventAction("import_shard_write")
            .field("package.group", coords.groupId)
            .field("file.path", coords.groupPath)
            .field("package.name", coords.artifactId)
            .field("package.version", coords.version)
            .log();
        final String shard = String.format(
            Locale.ROOT,
            "{\"groupId\":\"%s\",\"artifactId\":\"%s\",\"version\":\"%s\",\"path\":\"%s\",\"size\":%d,\"sha1\":%s,\"sha256\":%s,\"ts\":%d}",
            escapeJson(coords.groupId),
            escapeJson(coords.artifactId),
            escapeJson(coords.version),
            escapeJson(path),
            Long.valueOf(size),
            digests.getOrDefault(DigestType.SHA1, null) == null ? "null" : ("\"" + digests.get(DigestType.SHA1) + "\""),
            digests.getOrDefault(DigestType.SHA256, null) == null ? "null" : ("\"" + digests.get(DigestType.SHA256) + "\""),
            System.currentTimeMillis()
        );
        // Include filename in shard path to avoid overwrites when multiple artifacts have same version
        final String filename = path.substring(path.lastIndexOf('/') + 1);
        EcsLogger.debug("com.artipie.importer")
            .message("Extracted filename '" + filename + "' from path: " + path)
            .eventCategory("repository")
            .eventAction("import_shard_write")
            .log();
        // Build shard key parts, avoiding empty groupPath
        final java.util.List<String> keyParts = new java.util.ArrayList<>();
        keyParts.add(".meta");
        keyParts.add("maven");
        keyParts.add("shards");
        if (!coords.groupPath.isEmpty()) {
            // Split and filter out empty strings to handle consecutive slashes
            final String[] groupParts = coords.groupPath.split("/");
            for (final String part : groupParts) {
                if (!part.isEmpty()) {
                    keyParts.add(part);
                }
            }
        }
        keyParts.add(coords.artifactId);
        keyParts.add(coords.version);
        keyParts.add(filename + ".json");
        // Remove any empty parts that might have been added
        keyParts.removeIf(String::isEmpty);
        // Debug logging to identify empty parts
        EcsLogger.debug("com.artipie.importer")
            .message("Creating shard key (parts: " + keyParts.toString() + ")")
            .eventCategory("repository")
            .eventAction("import_shard_write")
            .log();
        final Key shardKey = new Key.From(keyParts.toArray(new String[0]));
        return storage.save(shardKey, new Content.From(shard.getBytes(StandardCharsets.UTF_8)))
            .thenRun(() -> EcsLogger.info("com.artipie.importer")
                .message("Shard written [maven]")
                .eventCategory("repository")
                .eventAction("import_shard_write")
                .eventOutcome("success")
                .field("package.group", coords.groupId)
                .field("package.name", coords.artifactId)
                .field("package.version", coords.version)
                .log());
    }

    /**
     * Write a Helm metadata shard for a single chart version under:
     * .meta/helm/shards/{name}/{version}.json
     */
    private static CompletionStage<Void> writeHelmShard(
        final Storage storage,
        final Key target,
        final ImportRequest request,
        final long size,
        final EnumMap<DigestType, String> digests,
        final Optional<String> baseUrl
    ) {
        final String path = target.string();
        final String file = path.substring(path.lastIndexOf('/') + 1);
        if (!file.toLowerCase(Locale.ROOT).endsWith(".tgz")) {
            return CompletableFuture.completedFuture(null);
        }
        // Parse Helm chart name and version properly for SemVer
        // Pattern: {name}-{version}.tgz where version can be SemVer with additional components
        final String withoutExt = file.substring(0, file.toLowerCase(Locale.ROOT).lastIndexOf(".tgz"));
        // Find the first dash that starts a version pattern (digit.digit or digit)
        int versionStart = -1;
        for (int i = 0; i < withoutExt.length(); i++) {
            if (withoutExt.charAt(i) == '-' && i + 1 < withoutExt.length()) {
                char next = withoutExt.charAt(i + 1);
                if (Character.isDigit(next)) {
                    // Check if this looks like a version start
                    String potential = withoutExt.substring(i + 1);
                    if (potential.matches("^\\d+(\\.\\d+)*([.-].*)?")) {
                        versionStart = i;
                        break;
                    }
                }
            }
        }
        if (versionStart <= 0) {
            EcsLogger.debug("com.artipie.importer")
                .message("Could not parse Helm name/version")
                .eventCategory("repository")
                .eventAction("import_shard_write")
                .field("file.name", file)
                .log();
            return CompletableFuture.completedFuture(null);
        }
        final String name = withoutExt.substring(0, versionStart);
        final String version = withoutExt.substring(versionStart + 1);
        final String url = baseUrl.map(b -> b + "/" + path).orElse(path);
        final String shard = String.format(
            Locale.ROOT,
            "{\"name\":\"%s\",\"version\":\"%s\",\"url\":\"%s\",\"path\":\"%s\",\"size\":%d,\"sha256\":%s,\"ts\":%d}",
            escapeJson(name),
            escapeJson(version),
            escapeJson(url),
            escapeJson(path),
            Long.valueOf(size),
            digests.getOrDefault(DigestType.SHA256, null) == null ? "null" : ("\"" + digests.get(DigestType.SHA256) + "\""),
            System.currentTimeMillis()
        );
        final Key shardKey = new Key.From(
            ".meta", "helm", "shards",
            name, version + ".json"
        );
        return storage.save(shardKey, new Content.From(shard.getBytes(StandardCharsets.UTF_8)))
            .thenRun(() -> EcsLogger.info("com.artipie.importer")
                .message("Shard written [helm]")
                .eventCategory("repository")
                .eventAction("import_shard_write")
                .eventOutcome("success")
                .field("package.name", name)
                .field("package.version", version)
                .log());
    }

    /**
     * Minimal Maven coordinates inference from storage path fallback.
     */
    private static MavenCoords inferMavenCoords(
        final String path,
        final String hdrArtifact,
        final String hdrVersion
    ) {
        EcsLogger.debug("com.artipie.importer")
            .message("inferMavenCoords called")
            .eventCategory("repository")
            .eventAction("maven_coords_infer")
            .field("url.path", path)
            .field("package.name", hdrArtifact)
            .field("package.version", hdrVersion)
            .log();
        // Check if path starts with slash and remove it
        String normalizedPath = path;
        if (path.startsWith("/")) {
            normalizedPath = path.substring(1);
            EcsLogger.debug("com.artipie.importer")
                .message("Normalized path")
                .eventCategory("repository")
                .eventAction("maven_coords_infer")
                .field("url.original", path)
                .field("url.path", normalizedPath)
                .log();
        }
        if (hdrArtifact != null && hdrVersion != null && !hdrVersion.isEmpty()) {
            EcsLogger.debug("com.artipie.importer")
                .message("Using headers for coordinates")
                .eventCategory("repository")
                .eventAction("maven_coords_infer")
                .log();
            final int idx = normalizedPath.lastIndexOf('/');
            if (idx > 0) {
                final String parent = normalizedPath.substring(0, idx);
                final int vIdx = parent.lastIndexOf('/');
                if (vIdx > 0) {
                    final String groupPath = parent.substring(0, vIdx);
                    final String groupId = groupPath.replace('/', '.');
                    return new MavenCoords(groupId, groupPath, hdrArtifact, hdrVersion);
                }
            }
        }
        EcsLogger.debug("com.artipie.importer")
            .message("Using path parsing fallback")
            .eventCategory("repository")
            .eventAction("maven_coords_infer")
            .log();
        // Fallback: .../{artifactId}/{version}/{file}
        final String[] segs = normalizedPath.split("/");
        EcsLogger.debug("com.artipie.importer")
            .message("Path segments")
            .eventCategory("repository")
            .eventAction("maven_coords_infer")
            .field("url.path", normalizedPath)
            .log();
        if (segs.length < 3) {
            EcsLogger.debug("com.artipie.importer")
                .message("Path has less than 3 segments, returning null")
                .eventCategory("repository")
                .eventAction("maven_coords_infer")
                .eventOutcome("failure")
                .log();
            return null;
        }
        // The last segment is the filename
        // The second-to-last segment is the version
        // The third-to-last segment is the artifactId
        // Everything before that is the groupPath
        final String filename = segs[segs.length - 1];
        final String version = segs[segs.length - 2];
        final String artifactId = segs[segs.length - 3];
        final String groupPath = String.join("/", java.util.Arrays.copyOf(segs, segs.length - 3));
        EcsLogger.debug("com.artipie.importer")
            .message("Parsed from path")
            .eventCategory("repository")
            .eventAction("maven_coords_infer")
            .field("file.name", filename)
            .field("package.name", artifactId)
            .field("package.version", version)
            .field("file.path", groupPath)
            .log();
        if (groupPath.isEmpty()) {
            return null;
        }
        final String groupId = groupPath.replace('/', '.');
        return new MavenCoords(groupId, groupPath, artifactId, version);
    }

    /**
     * Escape a value for embedding into simple JSON string template.
     */
    private static String escapeJson(final String val) {
        if (val == null) {
            return null;
        }
        return val.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Simple value object for Maven coordinates.
     */
    private static final class MavenCoords {
        final String groupId;
        final String groupPath;
        final String artifactId;
        final String version;
        MavenCoords(final String groupId, final String groupPath, final String artifactId, final String version) {
            this.groupId = groupId;
            this.groupPath = groupPath;
            this.artifactId = artifactId;
            this.version = version;
        }
    }

    /**
     * Sanitize idempotency key for storage.
     *
     * @param key Key
     * @param attempt Attempt number
     * @return Sanitized string
     */
    private static String sanitize(final String key, final int attempt) {
        final String base = key == null ? "" : key;
        final StringBuilder sanitized = new StringBuilder(base.length());
        for (int idx = 0; idx < base.length(); idx = idx + 1) {
            final char ch = base.charAt(idx);
            if (Character.isLetterOrDigit(ch) || ch == '-') {
                sanitized.append(ch);
            } else {
                sanitized.append('-');
            }
        }
        sanitized.append('-').append(attempt);
        return sanitized.toString();
    }

    /**
     * Resolve repository base URL from configuration.
     *
     * @param config Repository configuration
     * @return Optional base URL string without trailing slash
     */
    private static Optional<String> repositoryBaseUrl(final RepoConfig config) {
        try {
            final String raw = config.url().toString();
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            if (raw.endsWith("/")) {
                return Optional.of(raw.substring(0, raw.length() - 1));
            }
            return Optional.of(raw);
        } catch (final IllegalArgumentException ex) {
            EcsLogger.debug("com.artipie.importer")
                .message("Repository has no valid base URL")
                .eventCategory("configuration")
                .eventAction("base_url_resolve")
                .field("repository.name", config.name())
                .field("error.message", ex.getMessage())
                .log();
            return Optional.empty();
        }
    }

    /**
     * Enqueue metadata event.
     *
     * @param request Request
     * @param size Size
     */
    private void enqueueEvent(final ImportRequest request, final long size) {
        this.events.ifPresent(queue -> request.artifact().ifPresent(name -> {
            final long created = request.created().orElse(System.currentTimeMillis());
            final ArtifactEvent event = request.release()
                .map(release -> new ArtifactEvent(
                    request.repoType(),
                    request.repo(),
                    request.owner().orElse(ArtifactEvent.DEF_OWNER),
                    name,
                    request.version().orElse(""),
                    size,
                    created,
                    release
                ))
                .orElse(
                    new ArtifactEvent(
                        request.repoType(),
                        request.repo(),
                        request.owner().orElse(ArtifactEvent.DEF_OWNER),
                        name,
                        request.version().orElse(""),
                        size,
                        created
                    )
                );
            queue.offer(event);
        }));
    }
}
