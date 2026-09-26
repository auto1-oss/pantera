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
package com.auto1.pantera.conda.asto;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.lock.storage.IndexUpdateLock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Removes packages that are no longer in storage from a local conda
 * repository's {@code <subdir>/repodata.json} after a management-API delete.
 *
 * <p>The upload path ({@code UpdateSlice}) merges every published package into
 * its subdir's {@code repodata.json}; a delete that removed the package file
 * straight from storage left it listed, so {@code conda install} resolved it
 * and then failed downloading it with a 404. The pruner reconciles the index
 * with storage: every {@code packages} / {@code packages.conda} entry whose
 * file is gone is dropped. It runs under the same repodata lock as the
 * upload's merge, so a concurrent upload is neither lost nor resurrects the
 * deleted entry.</p>
 *
 * @since 2.2.9
 */
public final class RepodataPruner {

    /**
     * Repodata file name.
     */
    private static final String REPODATA = "repodata.json";

    /**
     * Package sections of repodata.
     */
    private static final List<String> SECTIONS = List.of("packages", "packages.conda");

    /**
     * Shared Jackson mapper.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Repository storage (repository-relative keys).
     */
    private final Storage asto;

    /**
     * Ctor.
     * @param asto Repository storage
     */
    public RepodataPruner(final Storage asto) {
        this.asto = asto;
    }

    /**
     * The key of a subdir's repodata, which is also the key the upload and
     * the delete lock while rewriting it.
     * @param subdir Subdir key, {@link Key#ROOT} for the repository root
     * @return Repodata key
     */
    private static Key repodata(final Key subdir) {
        final Key res;
        if (subdir.equals(Key.ROOT)) {
            res = new Key.From(RepodataPruner.REPODATA);
        } else {
            res = new Key.From(subdir, RepodataPruner.REPODATA);
        }
        return res;
    }

    /**
     * Prune the repodata a deleted path can be listed in: the subdir holding
     * a deleted package file, or the deleted folder itself.
     * @param deleted Deleted storage path, repository-relative
     * @return Names of the packages whose entries were removed
     */
    public CompletableFuture<Set<String>> afterDelete(final String deleted) {
        final String clean = RepodataPruner.trim(deleted);
        final Collection<Key> subdirs = new LinkedHashSet<>(2);
        if (!clean.isEmpty()) {
            final Key key = new Key.From(clean);
            subdirs.add(key.parent().orElse(Key.ROOT));
            subdirs.add(key);
        }
        CompletableFuture<Set<String>> res = CompletableFuture.completedFuture(new HashSet<>());
        for (final Key subdir : subdirs) {
            res = res.thenCompose(
                names -> this.prune(subdir).thenApply(
                    more -> {
                        names.addAll(more);
                        return names;
                    }
                )
            );
        }
        return res;
    }

    /**
     * Prune one subdir's repodata under the repodata lock.
     * @param subdir Subdir key
     * @return Names of the packages whose entries were removed
     */
    private CompletableFuture<Set<String>> prune(final Key subdir) {
        final Key index = RepodataPruner.repodata(subdir);
        return this.asto.exists(index).thenCompose(
            present -> {
                if (!present) {
                    return CompletableFuture.completedFuture(Set.of());
                }
                return new IndexUpdateLock(this.asto, index).run(
                    target -> target.exists(index).thenCompose(
                        still -> {
                            if (!still) {
                                return CompletableFuture.completedFuture(Set.<String>of());
                            }
                            return this.files(subdir).thenCompose(
                                files -> target.value(index)
                                    .thenCompose(Content::asBytesFuture)
                                    .thenCompose(
                                        bytes -> RepodataPruner.rewrite(
                                            target, index, bytes, files
                                        )
                                    )
                            );
                        }
                    )
                );
            }
        );
    }

    /**
     * Names of the files stored directly in a subdir.
     * @param subdir Subdir key
     * @return File names
     */
    private CompletableFuture<Set<String>> files(final Key subdir) {
        final int depth = subdir.equals(Key.ROOT) ? 0 : subdir.parts().size();
        return this.asto.list(subdir).thenApply(
            keys -> keys.stream()
                .filter(key -> key.parts().size() == depth + 1)
                .map(key -> key.parts().get(depth))
                .collect(Collectors.toSet())
        );
    }

    /**
     * Rewrite repodata without the entries of missing files.
     * @param target Storage
     * @param index Repodata key
     * @param bytes Repodata content
     * @param files Files present in the subdir
     * @return Names of the packages whose entries were removed
     */
    private static CompletableFuture<Set<String>> rewrite(
        final Storage target, final Key index, final byte[] bytes, final Set<String> files
    ) {
        final JsonNode root;
        try {
            root = RepodataPruner.MAPPER.readTree(bytes);
        } catch (final IOException err) {
            return CompletableFuture.failedFuture(new PanteraIOException(err));
        }
        final Set<String> removed = new HashSet<>();
        if (root instanceof ObjectNode) {
            for (final String section : RepodataPruner.SECTIONS) {
                final JsonNode packages = root.get(section);
                if (packages instanceof ObjectNode) {
                    removed.addAll(RepodataPruner.dropMissing((ObjectNode) packages, files));
                }
            }
        }
        if (removed.isEmpty()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        final byte[] out;
        try {
            out = RepodataPruner.MAPPER.writeValueAsBytes(root);
        } catch (final IOException err) {
            return CompletableFuture.failedFuture(new PanteraIOException(err));
        }
        return target.save(index, new Content.From(out)).thenApply(nothing -> removed);
    }

    /**
     * Drop the entries of a package section whose file is missing.
     * @param packages Package section
     * @param files Files present in the subdir
     * @return Names of the packages whose entries were removed
     */
    private static Set<String> dropMissing(final ObjectNode packages, final Set<String> files) {
        final Set<String> removed = new HashSet<>();
        final List<String> gone = new ArrayList<>(0);
        final Iterator<Map.Entry<String, JsonNode>> fields = packages.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> entry = fields.next();
            if (!files.contains(entry.getKey())) {
                gone.add(entry.getKey());
                final JsonNode name = entry.getValue().get("name");
                removed.add(name == null ? entry.getKey() : name.asText());
            }
        }
        gone.forEach(packages::remove);
        return removed;
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
}
