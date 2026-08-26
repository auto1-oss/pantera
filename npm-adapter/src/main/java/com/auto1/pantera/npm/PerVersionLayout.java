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
package com.auto1.pantera.npm;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

/**
 * Per-version file layout for NPM packages.
 *
 * <p>Eliminates lock contention by storing each version in its own file:</p>
 * <pre>
 * @scope/package/
 *   ├── .versions/
 *   │   ├── 1.0.0.json
 *   │   ├── 1.0.1.json
 *   │   └── 2.0.0.json
 *   ├── .dist-tags.json (durable dist-tags sidecar)
 *   ├── -/
 *   │   └── tarballs
 *   └── meta.json (generated on-demand)
 * </pre>
 *
 * <p>Benefits:</p>
 * <ul>
 *   <li>Each import writes ONE file (no lock contention between versions)</li>
 *   <li>132 versions = 132 parallel writes (not serial!)</li>
 *   <li>Lock-free: Different versions never compete</li>
 *   <li>Self-healing: meta.json regenerated on each read</li>
 * </ul>
 *
 * <p>Dist-tags are the one piece of state that cannot be derived purely from
 * the set of published version files (a custom tag such as {@code beta} or
 * {@code next} is an explicit client decision, not a semver computation), so
 * they are persisted in a small durable sidecar, {@code <pkg>/.dist-tags.json}.
 * When the sidecar is absent (packages published before this sidecar existed)
 * {@link #generateMetaJson(Key)} falls back to a computed {@code latest} —
 * no migration is required.</p>
 *
 * @since 1.18.13
 */
public final class PerVersionLayout {

    /**
     * Dist-tags json field name.
     */
    private static final String DIST_TAGS = "dist-tags";

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Ctor.
     *
     * @param storage Storage
     */
    public PerVersionLayout(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Add single version metadata to per-version file.
     * No locking needed - each version writes to its own file.
     *
     * @param packageKey Package key (e.g., "@scope/package")
     * @param version Version string
     * @param versionJson JSON metadata for this version
     * @return Completion stage
     */
    public CompletionStage<Void> addVersion(
        final Key packageKey,
        final String version,
        final JsonObject versionJson
    ) {
        final Key versionFile = this.versionFileKey(packageKey, version);

        // Add publish timestamp to version metadata if not present
        // This allows us to reconstruct the "time" object later
        final JsonObject versionWithTime;
        if (!versionJson.containsKey("_publishTime")) {
            final String now = java.time.Instant.now().toString();
            versionWithTime = Json.createObjectBuilder(versionJson)
                .add("_publishTime", now)
                .build();
        } else {
            versionWithTime = versionJson;
        }

        // Write directly - no locking needed!
        // Each version has its own file, so no contention
        final byte[] bytes = versionWithTime.toString().getBytes(StandardCharsets.UTF_8);
        return this.storage.save(versionFile, new Content.From(bytes))
            .toCompletableFuture();
    }

    /**
     * Read a single version's metadata from its per-version file.
     *
     * @param packageKey Package key
     * @param version Version string
     * @return Completion stage with the version json, or an empty object if the
     *  version does not exist
     */
    public CompletionStage<JsonObject> readVersion(final Key packageKey, final String version) {
        final Key key = this.versionFileKey(packageKey, version);
        return this.storage.exists(key).thenCompose(
            exists -> {
                if (exists) {
                    return this.storage.value(key).thenCompose(Content::asJsonObjectFuture);
                }
                return CompletableFuture.completedFuture(Json.createObjectBuilder().build());
            }
        );
    }

    /**
     * Overwrite a single version's per-version file (used to patch fields such
     * as {@code deprecated} without touching any other version).
     *
     * @param packageKey Package key
     * @param version Version string
     * @param versionJson New content for the version file
     * @return Completion stage
     */
    public CompletionStage<Void> writeVersion(
        final Key packageKey, final String version, final JsonObject versionJson
    ) {
        final byte[] bytes = versionJson.toString().getBytes(StandardCharsets.UTF_8);
        return this.storage.save(this.versionFileKey(packageKey, version), new Content.From(bytes));
    }

    /**
     * Delete a single version's per-version file. Used by single-version
     * unpublish so the version genuinely stops existing instead of surviving
     * to be re-added by the next {@code generateMetaJson} call.
     *
     * @param packageKey Package key
     * @param version Version string
     * @return Completion stage
     */
    public CompletionStage<Void> deleteVersion(final Key packageKey, final String version) {
        return this.storage.delete(this.versionFileKey(packageKey, version));
    }

    /**
     * List the actual version numbers currently published for a package, read
     * from each per-version file's own {@code version} field (not derived from
     * the sanitized filename, which is lossy for versions containing characters
     * outside {@code [a-zA-Z0-9._-]}, e.g. build metadata {@code +build.1}).
     *
     * @param packageKey Package key
     * @return Completion stage with the set of published version strings
     */
    public CompletionStage<Set<String>> listVersions(final Key packageKey) {
        return this.storage.list(this.versionsDir(packageKey)).thenCompose(
            versionFiles -> {
                final List<CompletableFuture<String>> reads = versionFiles.stream()
                    .filter(key -> key.string().endsWith(".json"))
                    .map(key -> this.storage.value(key)
                        .thenCompose(Content::asJsonObjectFuture)
                        .thenApply(json -> json.getString("version", null))
                        .exceptionally(err -> null)
                        .toCompletableFuture())
                    .collect(Collectors.toList());
                return CompletableFuture.allOf(reads.toArray(new CompletableFuture[0]))
                    .thenApply(
                        ignored -> reads.stream()
                            .map(CompletableFuture::join)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet())
                    );
            }
        );
    }

    /**
     * Read the durable dist-tags sidecar for a package.
     *
     * @param packageKey Package key
     * @return Completion stage with the tag-&gt;version map, empty when the
     *  sidecar does not exist (e.g. package predates the sidecar, or has no
     *  custom tags yet)
     */
    public CompletionStage<JsonObject> readDistTags(final Key packageKey) {
        final Key key = this.distTagsKey(packageKey);
        return this.storage.exists(key).thenCompose(
            exists -> {
                if (exists) {
                    return this.storage.value(key).thenCompose(Content::asJsonObjectFuture);
                }
                return CompletableFuture.completedFuture(Json.createObjectBuilder().build());
            }
        );
    }

    /**
     * Set (or overwrite) a single dist-tag.
     *
     * @param packageKey Package key
     * @param tag Tag name (e.g. "latest", "beta")
     * @param version Version the tag should point to
     * @return Completion stage
     */
    public CompletionStage<Void> writeTag(
        final Key packageKey, final String tag, final String version
    ) {
        return this.readDistTags(packageKey).thenCompose(
            tags -> this.saveDistTags(
                packageKey, Json.createObjectBuilder(tags).add(tag, version).build()
            )
        );
    }

    /**
     * Remove a single dist-tag.
     *
     * @param packageKey Package key
     * @param tag Tag name to remove
     * @return Completion stage
     */
    public CompletionStage<Void> removeTag(final Key packageKey, final String tag) {
        return this.readDistTags(packageKey).thenCompose(
            tags -> this.saveDistTags(
                packageKey, Json.createObjectBuilder(tags).remove(tag).build()
            )
        );
    }

    /**
     * Merge a tag-&gt;version map (as sent verbatim by the npm CLI in the
     * publish payload's {@code dist-tags} field) into the sidecar. A normal
     * publish sends {@code {"latest": "<version>"}}; a {@code --tag beta}
     * publish sends only {@code {"beta": "<version>"}} — the merge intentionally
     * leaves every other existing tag (including {@code latest}) untouched,
     * matching real npm registry semantics.
     *
     * @param packageKey Package key
     * @param tags Tag-&gt;version map to merge; a no-op when empty
     * @return Completion stage
     */
    public CompletionStage<Void> mergeDistTags(final Key packageKey, final JsonObject tags) {
        if (tags.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return this.readDistTags(packageKey).thenCompose(
            existing -> {
                final JsonObjectBuilder merged = Json.createObjectBuilder(existing);
                for (final String tag : tags.keySet()) {
                    merged.add(tag, tags.getString(tag));
                }
                return this.saveDistTags(packageKey, merged.build());
            }
        );
    }

    /**
     * Drop every dist-tag currently pointing at the given version. Used by
     * single-version unpublish: a tag whose target no longer exists must not
     * survive, and dropping {@code latest} lets {@link #generateMetaJson(Key)}
     * fall back to the recomputed highest remaining stable version.
     *
     * @param packageKey Package key
     * @param version Version being removed
     * @return Completion stage
     */
    public CompletionStage<Void> removeTagsPointingAt(final Key packageKey, final String version) {
        return this.readDistTags(packageKey).thenCompose(
            tags -> {
                final JsonObjectBuilder kept = Json.createObjectBuilder();
                boolean changed = false;
                for (final String tag : tags.keySet()) {
                    if (version.equals(tags.getString(tag))) {
                        changed = true;
                    } else {
                        kept.add(tag, tags.getString(tag));
                    }
                }
                if (!changed) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                return this.saveDistTags(packageKey, kept.build());
            }
        );
    }

    /**
     * Generate meta.json by aggregating all version files and merging the
     * durable dist-tags sidecar. This is called on-demand when clients request
     * meta.json or the dist-tags endpoint.
     *
     * @param packageKey Package key (e.g., "@scope/package")
     * @return Completion stage with aggregated meta.json
     */
    public CompletionStage<JsonObject> generateMetaJson(final Key packageKey) {
        final Key versionsDir = this.versionsDir(packageKey);

        return this.storage.list(versionsDir)
            .thenCompose(versionFiles -> {
                if (versionFiles.isEmpty()) {
                    // No versions found, return empty meta
                    return CompletableFuture.completedFuture(
                        Json.createObjectBuilder()
                            .add("versions", Json.createObjectBuilder())
                            .build()
                    );
                }

                // Read all version files in parallel
                final List<CompletableFuture<JsonObject>> futures = versionFiles.stream()
                    .filter(key -> key.string().endsWith(".json"))
                    .map(versionFile ->
                        this.storage.value(versionFile)
                            .thenCompose(Content::asJsonObjectFuture)
                            .toCompletableFuture()
                            .exceptionally(err -> {
                                // If a version file is corrupted, skip it
                                return Json.createObjectBuilder().build();
                            })
                    )
                    .collect(Collectors.toList());

                // Wait for all version files to be read
                return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenCompose(v -> this.buildMetaJson(packageKey, futures));
            });
    }

    /**
     * Build the aggregated meta.json object once every version file has been
     * read, merging the durable dist-tags sidecar over the computed
     * {@code latest}.
     *
     * @param packageKey Package key
     * @param futures Completed futures, one per version file
     * @return Completion stage with the aggregated meta.json
     */
    private CompletionStage<JsonObject> buildMetaJson(
        final Key packageKey, final List<CompletableFuture<JsonObject>> futures
    ) {
        // Merge all versions into meta.json structure
        final var versionsBuilder = Json.createObjectBuilder();
        final var metaBuilder = Json.createObjectBuilder();

        String packageName = null;

        for (final CompletableFuture<JsonObject> future : futures) {
            final JsonObject versionJson = future.join();

            if (versionJson.isEmpty()) {
                continue;  // Skip corrupted files
            }

            // Extract version number
            final String version = versionJson.getString("version", null);
            if (version == null) {
                continue;
            }

            // Extract package name (same for all versions)
            if (packageName == null) {
                packageName = versionJson.getString("name", packageKey.string());
            }

            // Add to versions map
            versionsBuilder.add(version, versionJson);
        }

        // Build versions object
        final JsonObject versionsObj = versionsBuilder.build();

        // Find latest STABLE version using semver (exclude prereleases)
        final String latestVersion;
        if (!versionsObj.isEmpty()) {
            final List<String> stableVersions = new com.auto1.pantera.npm.misc.DescSortedVersions(
                versionsObj,
                true  // excludePrereleases = true
            ).value();
            latestVersion = stableVersions.isEmpty() ? null : stableVersions.get(0);
        } else {
            latestVersion = null;
        }

        if (packageName != null) {
            metaBuilder.add("name", packageName);
        }
        return this.readDistTags(packageKey).thenApply(
            sidecarTags -> {
                final JsonObjectBuilder distTagsBuilder = Json.createObjectBuilder();
                if (latestVersion != null) {
                    distTagsBuilder.add("latest", latestVersion);
                }
                for (final String tag : sidecarTags.keySet()) {
                    distTagsBuilder.add(tag, sidecarTags.getString(tag));
                }
                metaBuilder.add(PerVersionLayout.DIST_TAGS, distTagsBuilder.build());
                metaBuilder.add("versions", versionsObj);
                return metaBuilder.build();
            }
        );
    }

    /**
     * Check if package has any versions.
     *
     * @param packageKey Package key
     * @return True if package has versions
     */
    public CompletionStage<Boolean> hasVersions(final Key packageKey) {
        final Key versionsDir = this.versionsDir(packageKey);
        return this.storage.list(versionsDir)
            .thenApply(keys -> !keys.isEmpty());
    }

    /**
     * Persist the given dist-tags map, replacing the sidecar wholesale.
     *
     * @param packageKey Package key
     * @param tags Full tag-&gt;version map to persist
     * @return Completion stage
     */
    private CompletionStage<Void> saveDistTags(final Key packageKey, final JsonObject tags) {
        final byte[] bytes = tags.toString().getBytes(StandardCharsets.UTF_8);
        return this.storage.save(this.distTagsKey(packageKey), new Content.From(bytes));
    }

    /**
     * Get key for per-version file.
     *
     * @param packageKey Package key (e.g., "@scope/package")
     * @param version Version string
     * @return Key to .versions/VERSION.json
     */
    private Key versionFileKey(final Key packageKey, final String version) {
        // Sanitize version string (remove invalid filename chars)
        final String sanitized = version.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new Key.From(packageKey, ".versions", sanitized + ".json");
    }

    /**
     * Get key for versions directory.
     *
     * @param packageKey Package key
     * @return Key to .versions/ directory
     */
    private Key versionsDir(final Key packageKey) {
        return new Key.From(packageKey, ".versions");
    }

    /**
     * Get key for the durable dist-tags sidecar.
     *
     * @param packageKey Package key
     * @return Key to .dist-tags.json
     */
    private Key distTagsKey(final Key packageKey) {
        return new Key.From(packageKey, ".dist-tags.json");
    }
}
