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
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCacheRegistry;
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
            default:
                res = CompletableFuture.completedFuture(null);
                break;
        }
        return res;
    }
}
