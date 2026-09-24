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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.composer.DeletedArchivePruner;
import com.auto1.pantera.conda.asto.RepodataPruner;
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCacheRegistry;
import com.auto1.pantera.gem.Gem;
import com.auto1.pantera.maven.metadata.MetadataVersionPruner;
import com.auto1.pantera.pypi.meta.PypiIndexCleanup;
import java.util.concurrent.CompletableFuture;

/**
 * Format metadata upkeep after a management-API delete of a path in a
 * <em>local</em> repository: the format's own index must stop listing what
 * was removed from storage, or clients resolve a version and then fail to
 * download it.
 *
 * <ul>
 *   <li>maven / gradle: the artifact-level {@code maven-metadata.xml}
 *       (and its checksums) drops versions that no longer exist; group
 *       caches of that artifact are invalidated.</li>
 *   <li>php (Composer): {@code p2/<vendor>/<package>.json} drops the
 *       versions whose archive was deleted.</li>
 *   <li>pypi: the pre-generated simple indexes are dropped so they are
 *       regenerated from storage; orphan yank sidecars are removed.</li>
 *   <li>conda: {@code <subdir>/repodata.json} drops the packages whose file
 *       is gone (under the repodata lock the upload merges under).</li>
 *   <li>gem: the {@code specs.4.8} family is rebuilt from the gems left
 *       ({@code latest_specs} falls back to the highest remaining version)
 *       and the quick specs of deleted gems are removed (under the index
 *       lock the upload indexes under).</li>
 * </ul>
 *
 * <p>Proxy repositories cache upstream metadata as-is and are left alone.</p>
 *
 * @since 2.2.9
 */
final class FormatDeleteHooks {

    /**
     * Run the upkeep for a repository type.
     * @param repoType Repository type
     * @param storage Repository storage (repository-relative keys)
     * @param path Deleted storage path
     * @return Completion
     */
    CompletableFuture<Void> afterDelete(
        final String repoType, final Storage storage, final String path
    ) {
        final CompletableFuture<Void> res;
        switch (repoType) {
            case "maven":
            case "gradle":
                res = new MetadataVersionPruner(storage).afterDelete(path)
                    .thenAccept(
                        changed -> changed.forEach(
                            name -> FilteredMetadataCacheRegistry.instance()
                                .invalidateAfterUpload("maven", name)
                        )
                    );
                break;
            case "php":
                res = new DeletedArchivePruner(storage).afterDelete(path)
                    .thenApply(removed -> null);
                break;
            case "pypi":
                res = new PypiIndexCleanup(storage).afterDelete(path);
                break;
            case "conda":
                res = new RepodataPruner(storage).afterDelete(path)
                    .thenAccept(
                        names -> names.forEach(
                            name -> FilteredMetadataCacheRegistry.instance()
                                .invalidateAfterUpload("conda", name)
                        )
                    );
                break;
            case "gem":
                res = FormatDeleteHooks.gem(storage, path);
                break;
            default:
                res = CompletableFuture.completedFuture(null);
                break;
        }
        return res;
    }

    /**
     * Rebuild a gem repository's index when the delete removed gems.
     * @param storage Repository storage
     * @param path Deleted storage path
     * @return Completion
     */
    private static CompletableFuture<Void> gem(final Storage storage, final String path) {
        String clean = path.trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        final CompletableFuture<Void> res;
        if ("gems".equals(clean) || clean.startsWith("gems/")) {
            final String file = clean.substring(clean.lastIndexOf('/') + 1);
            final int dash = file.lastIndexOf('-');
            res = new Gem(storage).reindex().toCompletableFuture().thenRun(
                () -> {
                    if (file.endsWith(".gem") && dash > 0) {
                        FilteredMetadataCacheRegistry.instance()
                            .invalidateAfterUpload("gem", file.substring(0, dash));
                    }
                }
            );
        } else {
            res = CompletableFuture.completedFuture(null);
        }
        return res;
    }
}
