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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Unpublishes the versions of a local npm package whose tarball a
 * management-API delete removed from storage.
 *
 * <p>The packument is generated from the per-version files under
 * {@code <pkg>/.versions/}, which a delete of {@code <pkg>/-/<pkg>-<v>.tgz}
 * (or of the whole {@code <pkg>/-} folder) did not touch, so npm kept
 * resolving the version and failed downloading it. The version file is
 * removed and every dist-tag pointing at it is dropped, exactly as a
 * single-version {@code npm unpublish} does; {@code latest} then falls back
 * to the highest remaining version. Deleting the package folder itself
 * removes its per-version files with it and needs nothing here.</p>
 *
 * @since 2.2.9
 */
public final class TarballDeletePruner {

    /**
     * Tarball directory segment.
     */
    private static final String TARBALLS = "/-";

    /**
     * Tarball extension.
     */
    private static final String TGZ = ".tgz";

    /**
     * Repository storage (repository-relative keys).
     */
    private final Storage storage;

    /**
     * Ctor.
     * @param storage Repository storage
     */
    public TarballDeletePruner(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Unpublish the versions whose tarball the delete removed.
     * @param deleted Deleted storage path, repository-relative
     * @return Completion
     */
    public CompletableFuture<Void> afterDelete(final String deleted) {
        String clean = deleted.trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        final int idx = clean.indexOf(TarballDeletePruner.TARBALLS + '/');
        final CompletableFuture<Void> res;
        if (idx > 0) {
            final String pkg = clean.substring(0, idx);
            final String file = clean.substring(idx + TarballDeletePruner.TARBALLS.length() + 1);
            if (file.startsWith(pkg + '-') && file.endsWith(TarballDeletePruner.TGZ)) {
                final String version = file.substring(
                    pkg.length() + 1, file.length() - TarballDeletePruner.TGZ.length()
                );
                res = new PerVersionLayout(this.storage).listVersions(new Key.From(pkg))
                    .toCompletableFuture()
                    .thenCompose(
                        versions -> this.unpublishMissing(
                            pkg,
                            versions.contains(version) ? List.of(version) : List.of()
                        )
                    );
            } else {
                res = CompletableFuture.completedFuture(null);
            }
        } else if (clean.endsWith(TarballDeletePruner.TARBALLS)
            && clean.length() > TarballDeletePruner.TARBALLS.length()) {
            final String pkg = clean.substring(
                0, clean.length() - TarballDeletePruner.TARBALLS.length()
            );
            res = new PerVersionLayout(this.storage).listVersions(new Key.From(pkg))
                .toCompletableFuture()
                .thenCompose(versions -> this.unpublishMissing(pkg, versions));
        } else {
            res = CompletableFuture.completedFuture(null);
        }
        return res;
    }

    /**
     * Unpublish each version whose tarball is not in storage.
     * @param pkg Package name
     * @param versions Candidate versions
     * @return Completion
     */
    private CompletableFuture<Void> unpublishMissing(
        final String pkg, final Collection<String> versions
    ) {
        final PerVersionLayout layout = new PerVersionLayout(this.storage);
        final Key key = new Key.From(pkg);
        CompletableFuture<Void> res = CompletableFuture.completedFuture(null);
        for (final String version : versions) {
            final Key tarball = new Key.From(
                pkg, "-", pkg + '-' + version + TarballDeletePruner.TGZ
            );
            res = res.thenCompose(
                nothing -> this.storage.exists(tarball).thenCompose(
                    stored -> {
                        if (stored) {
                            return CompletableFuture.<Void>completedFuture(null);
                        }
                        return layout.deleteVersion(key, version)
                            .thenCompose(ignored -> layout.removeTagsPointingAt(key, version))
                            .toCompletableFuture();
                    }
                )
            );
        }
        return res;
    }
}
