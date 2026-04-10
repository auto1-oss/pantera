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
package com.auto1.pantera.pypi.meta;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Utility for reading and writing PyPI sidecar metadata JSON files.
 *
 * <p>Sidecar files are stored at {@code .pypi/metadata/{package}/{filename}.json}
 * and hold supplementary metadata (requires-python, upload-time, yank status)
 * for individual distribution files.</p>
 *
 * @since 2.1.0
 */
public final class PypiSidecar {

    /**
     * Root folder for sidecar metadata inside the storage.
     */
    private static final String PYPI_META = ".pypi/metadata";

    /**
     * Utility class — no instances.
     */
    private PypiSidecar() {
    }

    /**
     * Writes a new sidecar file for the given artifact key.
     *
     * @param storage      Target storage
     * @param artifactKey  Key of the artifact (e.g. {@code requests/2.28.0/requests-2.28.0.tar.gz})
     * @param requiresPython Python version constraint string, may be empty or null
     * @param uploadTime   Upload timestamp
     * @return Completion future
     */
    public static CompletableFuture<Void> write(
        final Storage storage,
        final Key artifactKey,
        final String requiresPython,
        final Instant uploadTime
    ) {
        final JsonObjectBuilder builder = Json.createObjectBuilder()
            .add("upload-time", uploadTime.toString())
            .add("yanked", false);
        if (requiresPython == null || requiresPython.isEmpty()) {
            builder.addNull("requires-python");
        } else {
            builder.add("requires-python", requiresPython);
        }
        builder.addNull("yanked-reason");
        builder.addNull("dist-info-metadata");
        final String json = builder.build().toString();
        return storage.save(
            sidecarKey(artifactKey),
            new Content.From(json.getBytes(StandardCharsets.UTF_8))
        ).toCompletableFuture();
    }

    /**
     * Reads the sidecar for the given artifact key.
     *
     * @param storage     Source storage
     * @param artifactKey Key of the artifact
     * @return Future with {@link Optional} containing {@link Meta} if the sidecar exists,
     *         or {@link Optional#empty()} if it does not
     */
    public static CompletableFuture<Optional<Meta>> read(
        final Storage storage,
        final Key artifactKey
    ) {
        final Key sidecar = sidecarKey(artifactKey);
        return storage.exists(sidecar).thenCompose(exists -> {
            if (!exists) {
                // Self-healing: if no sidecar exists (artifact uploaded
                // before PEP 691/700 support, or sidecar write failed),
                // generate one from the storage backend's file metadata.
                // Uses OP_CREATED_AT as the upload-time — this is the
                // actual filesystem mtime or S3 LastModified, which is
                // the real upload timestamp. The sidecar is persisted
                // so this only computes once per artifact.
                return generateFromStorageMetadata(storage, artifactKey);
            }
            return storage.value(sidecar)
                .thenCompose(Content::asBytesFuture)
                .thenApply(bytes -> {
                    final String json = new String(bytes, StandardCharsets.UTF_8);
                    try (JsonReader reader = Json.createReader(new StringReader(json))) {
                        final JsonObject obj = reader.readObject();
                        final String rp = obj.isNull("requires-python")
                            ? null : obj.getString("requires-python");
                        final Instant time = Instant.parse(obj.getString("upload-time"));
                        final boolean yanked = obj.getBoolean("yanked", false);
                        final String yr = obj.isNull("yanked-reason")
                            ? null : obj.getString("yanked-reason");
                        final String dim = obj.isNull("dist-info-metadata")
                            ? null : obj.getString("dist-info-metadata");
                        return Optional.<Meta>of(
                            new Meta(rp, time, yanked, Optional.ofNullable(yr),
                                Optional.ofNullable(dim))
                        );
                    }
                });
        });
    }

    /**
     * Self-healing: generate a minimal sidecar from the storage
     * backend's file metadata when no sidecar file exists.
     *
     * <p>This covers artifacts uploaded before PEP 691/700 support
     * was added, without requiring re-upload or a separate backfill
     * CLI. The generated sidecar uses:</p>
     * <ul>
     *   <li>{@code upload-time}: from {@link com.auto1.pantera.asto.Meta#OP_CREATED_AT}
     *       — the filesystem mtime or S3 LastModified, which IS the
     *       real upload timestamp. Falls back to "now" if metadata
     *       is unavailable.</li>
     *   <li>{@code requires-python}: null (extracting from the wheel
     *       would require unzipping — too expensive for a read path).</li>
     *   <li>{@code yanked}: false.</li>
     * </ul>
     *
     * <p>The generated sidecar is persisted (fire-and-forget) so
     * subsequent reads are a single storage fetch. At scale with
     * 5M artifacts, this runs lazily (only for packages actually
     * requested) and amortizes to O(1) per artifact after the first
     * hit.</p>
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static CompletableFuture<Optional<Meta>> generateFromStorageMetadata(
        final Storage storage,
        final Key artifactKey
    ) {
        return storage.exists(artifactKey).thenCompose(artifactExists -> {
            if (!artifactExists) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return storage.metadata(artifactKey).thenCompose(fileMeta -> {
                final Instant uploadTime = fileMeta
                    .read(com.auto1.pantera.asto.Meta.OP_CREATED_AT)
                    .map(Instant.class::cast)
                    .orElse(Instant.now());
                // Persist the sidecar (fire-and-forget) so subsequent
                // reads hit the fast path. Errors are swallowed — the
                // in-memory Meta is returned regardless.
                write(storage, artifactKey, null, uploadTime)
                    .exceptionally(err -> null);
                return CompletableFuture.completedFuture(
                    Optional.of(new Meta(
                        null, uploadTime, false,
                        Optional.empty(), Optional.empty()
                    ))
                );
            }).exceptionally(err -> {
                // Storage metadata not available (e.g., in-memory storage
                // in tests). Fall back to "now" but don't persist — the
                // next read will try again.
                return Optional.of(new Meta(
                    null, Instant.now(), false,
                    Optional.empty(), Optional.empty()
                ));
            });
        });
    }

    /**
     * Marks a distribution as yanked, updating the sidecar in storage.
     *
     * @param storage     Target storage
     * @param artifactKey Key of the artifact
     * @param reason      Human-readable reason for yanking (may be null or empty)
     * @return Completion future
     */
    public static CompletableFuture<Void> yank(
        final Storage storage,
        final Key artifactKey,
        final String reason
    ) {
        return read(storage, artifactKey).thenCompose(optMeta -> {
            final Meta existing = optMeta.orElseThrow(() ->
                new IllegalStateException(
                    "Cannot yank: sidecar not found for " + artifactKey.string()
                )
            );
            final JsonObjectBuilder builder = Json.createObjectBuilder()
                .add("upload-time", existing.uploadTime().toString())
                .add("yanked", true);
            if (existing.requiresPython() == null || existing.requiresPython().isEmpty()) {
                builder.addNull("requires-python");
            } else {
                builder.add("requires-python", existing.requiresPython());
            }
            if (reason == null || reason.isEmpty()) {
                builder.addNull("yanked-reason");
            } else {
                builder.add("yanked-reason", reason);
            }
            existing.distInfoMetadata().ifPresentOrElse(
                dim -> builder.add("dist-info-metadata", dim),
                () -> builder.addNull("dist-info-metadata")
            );
            final String json = builder.build().toString();
            return storage.save(
                sidecarKey(artifactKey),
                new Content.From(json.getBytes(StandardCharsets.UTF_8))
            ).toCompletableFuture();
        });
    }

    /**
     * Clears the yank flag on a distribution, updating the sidecar in storage.
     *
     * @param storage     Target storage
     * @param artifactKey Key of the artifact
     * @return Completion future
     */
    public static CompletableFuture<Void> unyank(
        final Storage storage,
        final Key artifactKey
    ) {
        return read(storage, artifactKey).thenCompose(optMeta -> {
            final Meta existing = optMeta.orElseThrow(() ->
                new IllegalStateException(
                    "Cannot unyank: sidecar not found for " + artifactKey.string()
                )
            );
            final JsonObjectBuilder builder = Json.createObjectBuilder()
                .add("upload-time", existing.uploadTime().toString())
                .add("yanked", false)
                .addNull("yanked-reason");
            if (existing.requiresPython() == null || existing.requiresPython().isEmpty()) {
                builder.addNull("requires-python");
            } else {
                builder.add("requires-python", existing.requiresPython());
            }
            existing.distInfoMetadata().ifPresentOrElse(
                dim -> builder.add("dist-info-metadata", dim),
                () -> builder.addNull("dist-info-metadata")
            );
            final String json = builder.build().toString();
            return storage.save(
                sidecarKey(artifactKey),
                new Content.From(json.getBytes(StandardCharsets.UTF_8))
            ).toCompletableFuture();
        });
    }

    /**
     * Computes the storage key for the sidecar JSON file.
     *
     * <p>Given an artifact key such as {@code requests/2.28.0/requests-2.28.0.tar.gz},
     * the sidecar key is {@code .pypi/metadata/requests/requests-2.28.0.tar.gz.json}.</p>
     *
     * @param artifactKey Key of the artifact distribution file
     * @return Sidecar key
     */
    public static Key sidecarKey(final Key artifactKey) {
        final String full = artifactKey.string();
        final int lastSlash = full.lastIndexOf('/');
        final String filename;
        final String packageName;
        if (lastSlash < 0) {
            filename = full;
            packageName = full;
        } else {
            filename = full.substring(lastSlash + 1);
            // Package name is the first path segment
            final int firstSlash = full.indexOf('/');
            packageName = full.substring(0, firstSlash);
        }
        return new Key.From(PYPI_META, packageName, filename + ".json");
    }

    /**
     * Sidecar metadata record for a single distribution file.
     *
     * @param requiresPython  PEP 345 Requires-Python constraint, or null if absent
     * @param uploadTime      UTC timestamp of the upload
     * @param yanked          Whether the file has been yanked (PEP 592)
     * @param yankedReason    Optional human-readable yank reason
     * @param distInfoMetadata Optional dist-info metadata value (PEP 658)
     */
    public record Meta(
        String requiresPython,
        Instant uploadTime,
        boolean yanked,
        Optional<String> yankedReason,
        Optional<String> distInfoMetadata
    ) {
    }
}
