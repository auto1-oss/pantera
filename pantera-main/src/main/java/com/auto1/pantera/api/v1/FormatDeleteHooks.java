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
import com.auto1.pantera.helm.metadata.IndexYamlPruner;
import com.auto1.pantera.hex.http.ReleasesPruner;
import com.auto1.pantera.http.GoListPruner;
import com.auto1.pantera.maven.metadata.MetadataVersionPruner;
import com.auto1.pantera.npm.TarballDeletePruner;
import com.auto1.pantera.nuget.VersionsPruner;
import com.auto1.pantera.pypi.meta.PypiIndexCleanup;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
 *   <li>npm: a version whose tarball was deleted is unpublished (its
 *       per-version file and the dist-tags pointing at it are removed).</li>
 *   <li>helm: {@code index.yaml} drops the chart versions whose archive is
 *       gone.</li>
 *   <li>nuget: {@code <id>/index.json} drops the versions whose package is
 *       gone.</li>
 *   <li>go: {@code <module>/@v/list} lists the versions whose {@code .zip}
 *       is still stored.</li>
 *   <li>hexpm: {@code packages/<name>} drops the releases whose tarball is
 *       gone.</li>
 * </ul>
 *
 * <p>Proxy repositories cache upstream metadata as-is and are left alone.</p>
 *
 * @since 2.2.9
 */
final class FormatDeleteHooks {

    /**
     * Upkeep per repository type.
     */
    private static final Map<String, Hook> HOOKS = Map.ofEntries(
        Map.entry("maven", (storage, repo, path) -> FormatDeleteHooks.maven(storage, path)),
        Map.entry("gradle", (storage, repo, path) -> FormatDeleteHooks.maven(storage, path)),
        Map.entry(
            "php",
            (storage, repo, path) -> new DeletedArchivePruner(storage).afterDelete(path)
                .thenApply(removed -> null)
        ),
        Map.entry(
            "pypi", (storage, repo, path) -> new PypiIndexCleanup(storage).afterDelete(path)
        ),
        Map.entry(
            "conda",
            (storage, repo, path) -> new RepodataPruner(storage).afterDelete(path)
                .thenAccept(names -> FormatDeleteHooks.invalidate("conda", names))
        ),
        Map.entry("gem", (storage, repo, path) -> FormatDeleteHooks.gem(storage, path)),
        Map.entry(
            "npm",
            (storage, repo, path) -> new TarballDeletePruner(storage).afterDelete(path)
                .thenRun(() -> FormatDeleteHooks.npm(path))
        ),
        Map.entry(
            "helm",
            (storage, repo, path) -> new IndexYamlPruner(storage).afterDelete(path)
                .thenAccept(names -> FormatDeleteHooks.invalidate("helm", names))
        ),
        Map.entry(
            "nuget",
            (storage, repo, path) -> new VersionsPruner(storage).afterDelete(path)
                .thenAccept(
                    id -> {
                        if (id != null) {
                            FormatDeleteHooks.invalidate("nuget", List.of(id));
                        }
                    }
                )
        ),
        Map.entry(
            "go", (storage, repo, path) -> new GoListPruner(storage, repo).afterDelete(path)
        ),
        Map.entry(
            "hexpm",
            (storage, repo, path) -> new ReleasesPruner(storage).afterDelete(path)
                .thenAccept(names -> FormatDeleteHooks.invalidate("hexpm", names))
        )
    );

    /**
     * Run the upkeep for a repository type.
     * @param repoType Repository type
     * @param storage Repository storage (repository-relative keys)
     * @param repo Repository name
     * @param path Deleted storage path
     * @return Completion
     */
    CompletableFuture<Void> afterDelete(
        final String repoType, final Storage storage, final String repo, final String path
    ) {
        final Hook hook = FormatDeleteHooks.HOOKS.get(repoType);
        final CompletableFuture<Void> res;
        if (hook == null) {
            res = CompletableFuture.completedFuture(null);
        } else {
            res = hook.run(storage, repo, path);
        }
        return res;
    }

    /**
     * Maven / Gradle upkeep.
     * @param storage Repository storage
     * @param path Deleted storage path
     * @return Completion
     */
    private static CompletableFuture<Void> maven(final Storage storage, final String path) {
        return new MetadataVersionPruner(storage).afterDelete(path)
            .thenAccept(changed -> FormatDeleteHooks.invalidate("maven", changed));
    }

    /**
     * Rebuild a gem repository's index when the delete removed gems.
     * @param storage Repository storage
     * @param path Deleted storage path
     * @return Completion
     */
    private static CompletableFuture<Void> gem(final Storage storage, final String path) {
        final String clean = FormatDeleteHooks.trim(path);
        final CompletableFuture<Void> res;
        if ("gems".equals(clean) || clean.startsWith("gems/")) {
            final String file = clean.substring(clean.lastIndexOf('/') + 1);
            final int dash = file.lastIndexOf('-');
            res = new Gem(storage).reindex().toCompletableFuture().thenRun(
                () -> {
                    if (file.endsWith(".gem") && dash > 0) {
                        FormatDeleteHooks.invalidate("gem", List.of(file.substring(0, dash)));
                    }
                }
            );
        } else {
            res = CompletableFuture.completedFuture(null);
        }
        return res;
    }

    /**
     * Invalidate the filtered packument of the npm package a path belongs to.
     * @param path Deleted storage path
     */
    private static void npm(final String path) {
        final String clean = FormatDeleteHooks.trim(path);
        int idx = clean.indexOf("/-/");
        if (idx < 0 && clean.endsWith("/-")) {
            idx = clean.length() - 2;
        }
        if (idx > 0) {
            FormatDeleteHooks.invalidate("npm", List.of(clean.substring(0, idx)));
        }
    }

    /**
     * Invalidate the shared filtered-metadata caches of changed packages.
     * @param type Repository type the caches are keyed by
     * @param names Package names
     */
    private static void invalidate(final String type, final Collection<String> names) {
        names.forEach(
            name -> FilteredMetadataCacheRegistry.instance().invalidateAfterUpload(type, name)
        );
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
     * Format upkeep of one repository type.
     */
    @FunctionalInterface
    private interface Hook {
        /**
         * Run the upkeep.
         * @param storage Repository storage (repository-relative keys)
         * @param repo Repository name
         * @param path Deleted storage path
         * @return Completion
         */
        CompletableFuture<Void> run(Storage storage, String repo, String path);
    }
}
