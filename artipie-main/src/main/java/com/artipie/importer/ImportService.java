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
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.settings.repo.RepoConfig;
import com.artipie.settings.repo.Repositories;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service that orchestrates import persistence and metadata registration.
 *
 * @since 1.0
 */
public final class ImportService {

    /**
     * Logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ImportService.class);

    /**
     * Supported digest algorithms for runtime computation.
     */
    private static final Set<DigestType> DEFAULT_DIGESTS = Set.of(
        DigestType.SHA1, DigestType.SHA256, DigestType.MD5
    );

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
        this.repositories = repositories;
        this.sessions = sessions;
        this.events = events;
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

        final ImportSession session = this.sessions
            .map(store -> store.start(request))
            .orElseGet(() -> ImportSession.transientSession(request));

        if (session.status() == ImportSessionStatus.COMPLETED
            || session.status() == ImportSessionStatus.SKIPPED) {
            LOG.debug("Import skipped for key {} (already completed)", session.key());
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
            LOG.debug("Metadata-only import for {} :: {}", request.repo(), request.path());
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

        final Key target = new Key.From(request.path());
        final Key staging = stagingKey(session);
        final Key quarantine = quarantineKey(session);

        final DigestingContent digesting;
        final Content payload;
        if (request.policy() == ChecksumPolicy.COMPUTE) {
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
                    result -> finalizeImport(request, session, st, staging, target, quarantine, result)
                )
                .orTimeout(5, TimeUnit.MINUTES)  // Add timeout to prevent hanging futures
        ).toCompletableFuture().exceptionally(err -> {
            LOG.error("Import failed for {} :: {}: {}", request.repo(), request.path(), err.getMessage());
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
        final DigestResult result
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
            LOG.warn(
                "Checksum mismatch for {} :: {} -> {}",
                request.repo(),
                request.path(),
                mismatch.get()
            );
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
            .thenApply(
                ignored -> {
                    this.sessions.ifPresent(store -> store.markCompleted(session, size, toPersist));
                    this.enqueueEvent(request, size);
                    return new ImportResult(
                        ImportStatus.CREATED,
                        "Artifact imported",
                        toPersist,
                        size,
                        null
                    );
                }
            );
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
