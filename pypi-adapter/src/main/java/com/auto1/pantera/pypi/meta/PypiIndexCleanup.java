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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.pypi.NormalizedProjectName;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Brings a local PyPI repository's pre-generated indexes back in line with
 * storage after a delete.
 *
 * <p>The simple index is served from {@code .pypi/<pkg>/<pkg>.{html,json}}
 * and {@code .pypi/simple.{html,json}} whenever those files exist; only an
 * upload rewrote them. A deleted file therefore stayed listed and pip
 * picked it, then failed with a 404. The cached index files of the
 * affected package and the repository-level index are removed here, so the
 * next request regenerates them from what is actually stored (the
 * self-healing path of {@code SliceIndex}), and the yank sidecars of files
 * that no longer exist are removed so a re-upload under the same file name
 * does not inherit a stale yank.</p>
 *
 * @since 2.2.9
 */
public final class PypiIndexCleanup {

    /**
     * Metadata directory.
     */
    private static final String PYPI = ".pypi";

    /**
     * Sidecar directory.
     */
    private static final String SIDECARS = "metadata";

    /**
     * Repository storage (repository-relative keys).
     */
    private final Storage storage;

    /**
     * Ctor.
     * @param storage Repository storage
     */
    public PypiIndexCleanup(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Clean up after a delete.
     * @param deleted Deleted storage path, repository-relative
     * @return Completion
     */
    public CompletableFuture<Void> afterDelete(final String deleted) {
        String clean = deleted.trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        final int slash = clean.indexOf('/');
        final String dir = slash < 0 ? clean : clean.substring(0, slash);
        if (dir.isEmpty() || dir.startsWith(".")) {
            return CompletableFuture.completedFuture(null);
        }
        final Set<String> names = new LinkedHashSet<>(2);
        names.add(dir);
        names.add(new NormalizedProjectName.Simple(dir).value());
        final List<Key> stale = new ArrayList<>(2 + names.size() * 2);
        stale.add(new Key.From(PYPI, "simple.html"));
        stale.add(new Key.From(PYPI, "simple.json"));
        for (final String name : names) {
            stale.add(new Key.From(PYPI, name, name + ".html"));
            stale.add(new Key.From(PYPI, name, name + ".json"));
        }
        CompletableFuture<Void> res = CompletableFuture.completedFuture(null);
        for (final Key key : stale) {
            res = res.thenCompose(nothing -> this.deleteIfExists(key));
        }
        return res.thenCompose(nothing -> this.pruneSidecars(dir));
    }

    /**
     * Remove sidecars whose distribution file is gone.
     * @param dir Package directory
     * @return Completion
     */
    private CompletableFuture<Void> pruneSidecars(final String dir) {
        final String files = dir + "/";
        final String sidecars = String.join("/", PYPI, SIDECARS, dir) + "/";
        return this.storage.list(new Key.From(dir)).thenCompose(stored -> {
            final Set<String> present = new HashSet<>();
            for (final Key key : stored) {
                final String str = key.string();
                if (str.startsWith(files)) {
                    present.add(str.substring(str.lastIndexOf('/') + 1));
                }
            }
            return this.storage.list(new Key.From(PYPI, SIDECARS, dir)).thenCompose(meta -> {
                CompletableFuture<Void> res = CompletableFuture.completedFuture(null);
                for (final Key key : meta) {
                    final String str = key.string();
                    if (!str.startsWith(sidecars) || !str.endsWith(".json")) {
                        continue;
                    }
                    final String file = str.substring(sidecars.length(), str.length() - 5);
                    if (!present.contains(file)) {
                        res = res.thenCompose(nothing -> this.storage.delete(key));
                    }
                }
                return res;
            });
        });
    }

    /**
     * Delete a key when it exists.
     * @param key Key
     * @return Completion
     */
    private CompletableFuture<Void> deleteIfExists(final Key key) {
        return this.storage.exists(key).thenCompose(
            exists -> exists ? this.storage.delete(key) : CompletableFuture.completedFuture(null)
        );
    }
}
