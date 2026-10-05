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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.cooldown.metadata.ProxyMetadataRevalidators;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.cache.NegativeCacheKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The in-memory / Valkey caches a proxy repository keeps for a cached path,
 * dropped when that path is evicted (never by asking the upstream):
 * <ul>
 *   <li>the negative (404) cache, L1 and L2 -- the path's key, or every
 *   key of the repository for a folder;</li>
 *   <li>maven / gradle: the proxy's {@code maven-metadata.xml} cache of the
 *   artifact, through the adapter's own invalidation hook;</li>
 *   <li>maven / gradle, npm, pypi: the cooldown-filtered metadata envelope
 *   of the package.</li>
 * </ul>
 * Go, composer and file proxies keep their metadata in storage only.
 *
 * @since 2.2.10
 */
public final class ProxyPathCaches {

    /**
     * Maven metadata file name.
     */
    private static final String MAVEN_METADATA = "maven-metadata.xml";

    /**
     * npm tarball separator.
     */
    private static final String NPM_TARBALL = "/-/";

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * Repository type, e.g. {@code maven-proxy}.
     */
    private final String type;

    /**
     * Negative cache, if any.
     */
    private final Optional<NegativeCache> negative;

    /**
     * Envelope invalidation: (repository type, package name).
     */
    private final BiConsumer<String, String> envelopes;

    /**
     * Raw-metadata invalidation hooks by repository name.
     */
    private final Function<String, Optional<ProxyMetadataRevalidators.Revalidator>> hooks;

    /**
     * Ctor.
     * @param repo Repository name
     * @param type Repository type
     * @param negative Negative cache, if any
     * @param envelopes Envelope invalidation by (repository type, package)
     * @param hooks Raw-metadata invalidation hooks by repository name
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public ProxyPathCaches(
        final String repo, final String type, final Optional<NegativeCache> negative,
        final BiConsumer<String, String> envelopes,
        final Function<String, Optional<ProxyMetadataRevalidators.Revalidator>> hooks
    ) {
        this.repo = repo;
        this.type = type;
        this.negative = negative;
        this.envelopes = envelopes;
        this.hooks = hooks;
    }

    /**
     * Drop what the caches hold for an evicted path.
     * @param path Repository-relative path (no surrounding slashes)
     * @param folder Whether the path is a directory
     * @return Whether a metadata cache tier held state that was dropped
     */
    public CompletableFuture<Boolean> evict(final String path, final boolean folder) {
        this.negative.ifPresent(
            cache -> {
                if (folder) {
                    cache.invalidateMatching(key -> this.repo.equals(key.scope()));
                } else {
                    cache.invalidate(NegativeCacheKey.fromPath(this.repo, this.type, path));
                }
            }
        );
        final String family = this.type.toLowerCase(Locale.ROOT);
        final CompletableFuture<Boolean> res;
        if (family.startsWith("maven") || family.startsWith("gradle")) {
            res = this.maven(path, folder);
        } else {
            if (family.startsWith("npm")) {
                ProxyPathCaches.npmPackage(path, folder).ifPresent(this::envelope);
            } else if (family.startsWith("pypi")) {
                ProxyPathCaches.pypiProject(path, folder).ifPresent(this::envelope);
            }
            res = CompletableFuture.completedFuture(false);
        }
        return res;
    }

    /**
     * Maven: the metadata cache and envelopes of every artifact directory
     * the path may belong to (a file's version directory and artifact
     * directory; a folder and its parent).
     * @param path Path
     * @param folder Whether the path is a directory
     * @return Whether a metadata cache was invalidated
     */
    private CompletableFuture<Boolean> maven(final String path, final boolean folder) {
        // maven-metadata.xml (and its checksums) is never stored by a maven
        // proxy, so it reaches here as "not a stored file"; it is one all
        // the same.
        final boolean metadata = path.endsWith(ProxyPathCaches.MAVEN_METADATA)
            || path.contains(ProxyPathCaches.MAVEN_METADATA + ".");
        final List<String> dirs = new ArrayList<>(2);
        final String first;
        if (folder && !metadata) {
            first = path;
        } else {
            first = ProxyPathCaches.parent(path);
        }
        if (!first.isEmpty()) {
            dirs.add(first);
            final String second = ProxyPathCaches.parent(first);
            if (!second.isEmpty()) {
                dirs.add(second);
            }
        }
        final Optional<ProxyMetadataRevalidators.Revalidator> hook = this.hooks.apply(this.repo);
        final List<CompletableFuture<Boolean>> dropped = new ArrayList<>(dirs.size());
        for (final String dir : dirs) {
            this.envelope(dir.replace('/', '.'));
            hook.ifPresent(
                rev -> dropped.add(
                    rev.revalidate(dir)
                        .thenApply("invalidated"::equals)
                        .exceptionally(err -> false)
                )
            );
        }
        return CompletableFuture.allOf(dropped.toArray(new CompletableFuture[0])).thenApply(
            nothing -> metadata && dropped.stream().anyMatch(CompletableFuture::join)
        );
    }

    /**
     * Invalidate the filtered-metadata envelope of a package.
     * @param pkg Package name
     */
    private void envelope(final String pkg) {
        if (!pkg.isEmpty()) {
            this.envelopes.accept(this.type, pkg);
        }
    }

    /**
     * npm package a path belongs to: the part before {@code /-/} of a
     * tarball, the directory of a packument file, or the folder itself.
     * @param path Path
     * @param folder Whether the path is a directory
     * @return Package name
     */
    private static Optional<String> npmPackage(final String path, final boolean folder) {
        final int tarball = path.indexOf(ProxyPathCaches.NPM_TARBALL);
        final String pkg;
        if (tarball > 0) {
            pkg = path.substring(0, tarball);
        } else if (folder) {
            pkg = path;
        } else {
            pkg = ProxyPathCaches.parent(path);
        }
        return Optional.of(pkg).filter(name -> !name.isEmpty() && !name.startsWith("-"));
    }

    /**
     * PyPI project a path belongs to: an index key ({@code foo-bar},
     * {@code foo-bar.json}) or a distribution file under {@code packages/},
     * normalized as PEP 503 does.
     * @param path Path
     * @param folder Whether the path is a directory
     * @return Normalized project name
     */
    private static Optional<String> pypiProject(final String path, final boolean folder) {
        final String name;
        if (path.startsWith("packages/") || path.contains("/")) {
            if (folder) {
                name = "";
            } else {
                final String file = path.substring(path.lastIndexOf('/') + 1);
                final int dash = file.indexOf('-');
                name = dash > 0 ? file.substring(0, dash) : "";
            }
        } else if (path.endsWith(".json")) {
            name = path.substring(0, path.length() - ".json".length());
        } else {
            name = path;
        }
        return Optional.of(
            name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-")
        ).filter(proj -> !proj.isEmpty() && !"_root_index".equals(name));
    }

    /**
     * Parent of a path.
     * @param path Path
     * @return Parent, empty at the top level
     */
    private static String parent(final String path) {
        final int slash = path.lastIndexOf('/');
        final String res;
        if (slash > 0) {
            res = path.substring(0, slash);
        } else {
            res = "";
        }
        return res;
    }
}
