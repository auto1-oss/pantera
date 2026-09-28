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
package com.auto1.pantera.composer;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;

/**
 * Removes the versions whose archive was deleted from a local Composer
 * repository's {@code p2/<vendor>/<package>.json} (and {@code ~dev.json})
 * metadata. A version entry is dropped when its {@code dist.url} points at
 * the deleted archive or into a deleted folder; a package left with no
 * version is removed, and so is a metadata file left with no package.
 *
 * <p>Without this the version stayed in {@code p2}, Composer locked it and
 * then failed downloading its archive with a 404.</p>
 *
 * @since 2.2.9
 */
public final class DeletedArchivePruner {

    /**
     * Archive directory of the local layout.
     */
    private static final String ARTIFACTS = "artifacts";

    /**
     * Metadata directory.
     */
    private static final String P2 = "p2";

    /**
     * Repository storage (repository-relative keys).
     */
    private final Storage storage;

    /**
     * Ctor.
     * @param storage Repository storage
     */
    public DeletedArchivePruner(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Prune metadata after a delete.
     * @param deleted Deleted storage path, repository-relative
     * @return Number of version entries removed
     */
    public CompletableFuture<Integer> afterDelete(final String deleted) {
        final String clean = DeletedArchivePruner.trim(deleted);
        final String[] parts = clean.split("/");
        if (parts.length == 0 || !ARTIFACTS.equals(parts[0])) {
            return CompletableFuture.completedFuture(0);
        }
        return this.metadataFiles(parts).thenCompose(files -> {
            CompletableFuture<Integer> res = CompletableFuture.completedFuture(0);
            for (final Key file : files) {
                res = res.thenCompose(
                    total -> this.prune(file, clean).thenApply(count -> total + count)
                );
            }
            return res;
        });
    }

    /**
     * The p2 files that can reference the deleted path.
     * @param parts Deleted path segments, starting with {@code artifacts}
     * @return Metadata keys
     */
    private CompletableFuture<Collection<Key>> metadataFiles(final String... parts) {
        final CompletableFuture<Collection<Key>> res;
        if (parts.length >= 3) {
            final String pkg = parts[1] + "/" + parts[2];
            res = CompletableFuture.completedFuture(
                List.of(new Key.From(P2, pkg + ".json"), new Key.From(P2, pkg + "~dev.json"))
            );
        } else if (parts.length == 2) {
            res = this.storage.list(new Key.From(P2, parts[1]));
        } else {
            res = this.storage.list(new Key.From(P2));
        }
        return res;
    }

    /**
     * Prune one metadata file under its lock.
     * @param file Metadata key
     * @param deleted Deleted path
     * @return Number of version entries removed
     */
    private CompletableFuture<Integer> prune(final Key file, final String deleted) {
        if (!file.string().endsWith(".json")) {
            return CompletableFuture.completedFuture(0);
        }
        return this.storage.exclusively(
            file,
            target -> target.exists(file).thenCompose(exists -> {
                if (!exists) {
                    return CompletableFuture.completedFuture(0);
                }
                return target.value(file)
                    .thenCompose(Content::asJsonObjectFuture)
                    .thenCompose(json -> DeletedArchivePruner.rewrite(target, file, json, deleted));
            })
        ).toCompletableFuture();
    }

    /**
     * Rewrite a metadata document without the deleted versions.
     * @param target Storage
     * @param file Metadata key
     * @param json Metadata
     * @param deleted Deleted path
     * @return Number of version entries removed
     */
    private static CompletableFuture<Integer> rewrite(
        final Storage target, final Key file, final JsonObject json, final String deleted
    ) {
        final JsonObject packages = json.getJsonObject("packages");
        if (packages == null) {
            return CompletableFuture.completedFuture(0);
        }
        final JsonObjectBuilder kept = Json.createObjectBuilder();
        int removed = 0;
        int left = 0;
        for (final String name : packages.keySet()) {
            final JsonValue versions = packages.get(name);
            final Pruned pruned = DeletedArchivePruner.pruneVersions(versions, deleted);
            removed += pruned.removed;
            if (pruned.remaining > 0) {
                kept.add(name, pruned.value);
                left += 1;
            }
        }
        if (removed == 0) {
            return CompletableFuture.completedFuture(0);
        }
        final int count = removed;
        if (left == 0) {
            return target.delete(file).thenApply(nothing -> count);
        }
        final JsonObjectBuilder doc = Json.createObjectBuilder(json).add("packages", kept);
        return target.save(
            file, new Content.From(doc.build().toString().getBytes(StandardCharsets.UTF_8))
        ).thenApply(nothing -> count);
    }

    /**
     * Drop the version entries whose dist points at the deleted path.
     * @param versions Versions of one package (object keyed by version, or array)
     * @param deleted Deleted path
     * @return Pruned versions
     */
    private static Pruned pruneVersions(final JsonValue versions, final String deleted) {
        final Pruned res;
        if (versions.getValueType() == JsonValue.ValueType.OBJECT) {
            final JsonObject map = versions.asJsonObject();
            final JsonObjectBuilder kept = Json.createObjectBuilder();
            int remaining = 0;
            for (final String version : map.keySet()) {
                final JsonValue entry = map.get(version);
                if (!DeletedArchivePruner.points(entry, deleted)) {
                    kept.add(version, entry);
                    remaining += 1;
                }
            }
            res = new Pruned(kept.build(), map.size() - remaining, remaining);
        } else if (versions.getValueType() == JsonValue.ValueType.ARRAY) {
            final List<JsonValue> list = versions.asJsonArray();
            final JsonArrayBuilder kept = Json.createArrayBuilder();
            int remaining = 0;
            for (final JsonValue entry : list) {
                if (!DeletedArchivePruner.points(entry, deleted)) {
                    kept.add(entry);
                    remaining += 1;
                }
            }
            res = new Pruned(kept.build(), list.size() - remaining, remaining);
        } else {
            res = new Pruned(versions, 0, 1);
        }
        return res;
    }

    /**
     * Whether a version entry's {@code dist.url} is the deleted archive or
     * lies in a deleted folder.
     * @param entry Version entry
     * @param deleted Deleted path
     * @return True when it points there
     */
    private static boolean points(final JsonValue entry, final String deleted) {
        if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
            return false;
        }
        final JsonObject dist = entry.asJsonObject().getJsonObject("dist");
        if (dist == null || !dist.containsKey("url")
            || dist.get("url").getValueType() != JsonValue.ValueType.STRING) {
            return false;
        }
        String url = dist.getString("url");
        final int query = url.indexOf('?');
        if (query >= 0) {
            url = url.substring(0, query);
        }
        final String needle = "/" + deleted;
        return url.endsWith(needle) || url.contains(needle + "/") || url.equals(deleted)
            || url.startsWith(deleted + "/");
    }

    /**
     * Strip surrounding slashes.
     * @param path Path
     * @return Clean path
     */
    private static String trim(final String path) {
        String clean = path.trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    /**
     * Pruning outcome of one package.
     */
    private static final class Pruned {
        /**
         * Remaining versions value.
         */
        private final JsonValue value;

        /**
         * Removed entries.
         */
        private final int removed;

        /**
         * Remaining entries.
         */
        private final int remaining;

        Pruned(final JsonValue value, final int removed, final int remaining) {
            this.value = value;
            this.removed = removed;
            this.remaining = remaining;
        }
    }
}
