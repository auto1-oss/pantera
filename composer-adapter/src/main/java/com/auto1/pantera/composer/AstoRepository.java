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
import com.auto1.pantera.composer.http.Archive;

import javax.json.Json;
import javax.json.JsonObject;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * PHP Composer repository that stores packages in a {@link Storage}.
 */
public final class AstoRepository implements Repository {

    /**
     * Key to all packages.
     */
    public static final Key ALL_PACKAGES = new AllPackages();

    /**
     * The storage.
     */
    private final Storage asto;

    /**
     * Prefix with url for uploaded archive.
     */
    private final Optional<String> prefix;

    /**
     * Satis layout handler for lock-free per-package metadata.
     */
    private final SatisLayout satis;


    /**
     * Ctor.
     * @param storage Storage to store all repository data.
     */
    public AstoRepository(final Storage storage) {
        this(storage, Optional.empty(), Optional.empty());
    }

    /**
     * Ctor.
     * @param storage Storage to store all repository data.
     * @param prefix Prefix with url for uploaded archive.
     */
    public AstoRepository(final Storage storage, final Optional<String> prefix) {
        this(storage, prefix, Optional.empty());
    }

    /**
     * Ctor.
     * @param storage Storage to store all repository data
     * @param prefix Base URL for uploaded archive
     * @param repo Repository name
     */
    public AstoRepository(
        final Storage storage,
        final Optional<String> prefix,
        final Optional<String> repo
    ) {
        this.asto = storage;
        this.prefix = prefix.map(url -> AstoRepository.ensureRepoUrl(url, repo));
        this.satis = new SatisLayout(storage, this.prefix);
    }

    @Override
    public CompletionStage<Optional<Packages>> packages() {
        return this.packages(AstoRepository.ALL_PACKAGES);
    }

    @Override
    public CompletionStage<Optional<Packages>> packages(final Name name) {
        return this.packages(name.key());
    }

    @Override
    public CompletableFuture<Void> addJson(final Content content, final Optional<String> vers) {
        final Key key = new Key.From(UUID.randomUUID().toString());
        return this.asto.save(key, content).thenCompose(
            nothing -> this.asto.value(key)
                .thenCompose(Content::asBytesFuture)
                .thenCompose(bytes -> {
                    final Package pack = new JsonPackage(bytes);
                    return pack.name().thenCompose(
                        name -> this.updatePackages(AstoRepository.ALL_PACKAGES, pack, vers)
                            .thenCompose(ignored -> this.updatePackages(name.key(), pack, vers))
                    ).thenCompose(
                        ignored -> this.asto.delete(key)
                    );
                })
        );
    }

    @Override
    public CompletableFuture<Void> addArchive(final Archive archive, final Content content) {
        final Key key = archive.name().artifact();
        final Key tmp = new Key.From(String.format("%s.tmp", UUID.randomUUID()));
        return this.asto.save(key, content)
            .thenCompose(
                nothing -> this.asto.value(key)
                    .thenCompose(
                        cont -> archive.composerFrom(cont)
                            .thenApply(
                                compos -> AstoRepository.addVersion(compos, archive.name())
                            ).thenCombine(
                                this.asto.value(key),
                                (compos, cnt) -> archive.replaceComposerWith(
                                    cnt,
                                    compos.toString()
                                        .getBytes(StandardCharsets.UTF_8)
                                ).thenCompose(arch -> this.asto.save(tmp, arch))
                                .thenCompose(noth -> this.asto.delete(key))
                                .thenCompose(noth -> this.asto.move(tmp, key))
                                .thenCompose(
                                    noth -> {
                                        final Package pack = new JsonPackage(this.addDist(compos, key));
                                        return pack.name().thenCompose(
                                            name -> this.updatePackages(AstoRepository.ALL_PACKAGES, pack, Optional.empty())
                                                .thenCompose(ignored -> this.updatePackages(name.key(), pack, Optional.empty()))
                                        );
                                    }
                                )
                            ).thenCompose(Function.identity())
                    )
            );
    }

    @Override
    public CompletableFuture<Content> value(final Key key) {
        return this.asto.value(key);
    }

    @Override
    public Storage storage() {
        return this.asto;
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key key) {
        return this.asto.exists(key);
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        return this.asto.save(key, content);
    }

    @Override
    public <T> CompletionStage<T> exclusively(
        final Key key,
        final Function<Storage, CompletionStage<T>> operation
    ) {
        return this.asto.exclusively(key, operation);
    }

    @Override
    public CompletableFuture<Void> move(final Key source, final Key destination) {
        return this.asto.move(source, destination);
    }

    @Override
    public CompletableFuture<Void> delete(final Key key) {
        return this.asto.delete(key);
    }

    /**
     * Add version field to composer json.
     * @param compos Composer json file
     * @param name Instance of name for obtaining version
     * @return Composer json with added version.
     */
    private static JsonObject addVersion(final JsonObject compos, final Archive.Name name) {
        return Json.createObjectBuilder(compos)
            .add(JsonPackage.VRSN, name.version())
            .build();
    }

    /**
     * Add `dist` field to composer json.
     * @param compos Composer json file
     * @param path Prefix path for uploading archive (includes extension)
     * @return Composer json with added `dist` field.
     */
    private byte[] addDist(final JsonObject compos, final Key path) {
        final String url = this.prefix.orElseThrow(
            () -> new IllegalStateException("Prefix url for `dist` for uploaded archive was empty.")
        ).replaceAll("/$", "");
        
        // Detect archive type from path extension
        final String pathStr = path.string();
        final String distType = pathStr.endsWith(".tar.gz") || pathStr.endsWith(".tgz") 
            ? "tar" 
            : "zip";
        
        // Build full URL by appending path to base URL
        // Note: URI.resolve() with absolute paths replaces the path, so we concatenate instead
        final String fullUrl;
        if (pathStr.startsWith("/")) {
            // Path is absolute, append to base URL
            fullUrl = url.endsWith("/") ? url + pathStr.substring(1) : url + pathStr;
        } else {
            // Path is relative, ensure proper separation
            fullUrl = url.endsWith("/") ? url + pathStr : url + "/" + pathStr;
        }
        
        return Json.createObjectBuilder(compos).add(
            "dist", Json.createObjectBuilder()
                .add("url", fullUrl)
                .add("type", distType)
                .build()
        ).build()
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Ensure repository URL contains repository name as the last segment.
     * @param base Base URL from configuration
     * @param repo Repository name
     * @return Base URL guaranteed to end with the repository name segment
     */
    private static String ensureRepoUrl(final String base, final Optional<String> repo) {
        if (repo.isEmpty() || repo.get().isBlank()) {
            return base;
        }
        final String normalizedRepo = repo.get().trim()
            .replaceAll("^/+", "")
            .replaceAll("/+$", "");
        if (normalizedRepo.isEmpty()) {
            return base;
        }
        try {
            final URI uri = new URI(base);
            final String path = uri.getPath();
            final List<String> segments = new ArrayList<>();
            if (path != null && !path.isBlank()) {
                for (final String segment : path.split("/")) {
                    if (!segment.isEmpty()) {
                        segments.add(segment);
                    }
                }
            }
            if (segments.isEmpty() || !segments.get(segments.size() - 1).equals(normalizedRepo)) {
                segments.add(normalizedRepo);
            }
            final String newPath = "/" + String.join("/", segments);
            final URI updated = new URI(
                uri.getScheme(),
                uri.getUserInfo(),
                uri.getHost(),
                uri.getPort(),
                newPath,
                uri.getQuery(),
                uri.getFragment()
            );
            return updated.toString();
        } catch (final URISyntaxException ex) {
            return base;
        }
    }

    /**
     * Update package metadata using Satis layout (per-package files).
     * 
     * <p>For ALL_PACKAGES key: Skip update (root packages.json generated on-demand)</p>
     * <p>For per-package keys: Use Satis layout with per-package file locking</p>
     * 
     * @param metadataKey Key to metadata file
     * @param pack Package to add
     * @param version Version to add
     * @return Completion stage
     */
    private CompletionStage<Void> updatePackages(
        final Key metadataKey,
        final Package pack,
        final Optional<String> version
    ) {
        // If updating global packages.json (ALL_PACKAGES), skip it
        // In Satis model, root packages.json is generated on-demand
        if (metadataKey.equals(AstoRepository.ALL_PACKAGES)) {
            // Skip global packages.json update - eliminates bottleneck!
            // Root packages.json will be generated on read with provider references
            return CompletableFuture.completedFuture(null);
        }
        
        // Use Satis layout for per-package metadata
        // Each package has its own file in p2/ directory
        // This eliminates lock contention between different packages
        return this.satis.addPackageVersion(pack, version);
    }

    /**
     * Reads packages description from storage.
     *
     * @param key Content location in storage.
     * @return Packages found by name, might be empty.
     */
    private CompletionStage<Optional<Packages>> packages(final Key key) {
        // If reading root packages.json (ALL_PACKAGES), generate it on-demand from p2/
        if (key.equals(AstoRepository.ALL_PACKAGES)) {
            return this.satis.generateRootPackagesJson()
                .thenCompose(nothing -> this.asto.value(key))
                .thenApply(content -> (Packages) new JsonPackages(content))
                .thenApply(Optional::of)
                .exceptionally(err -> {
                    // If generation fails, return empty (repo might be empty)
                    return Optional.empty();
                });
        }
        
        // For per-package reads, use existing logic
        return this.asto.exists(key).thenCompose(
            exists -> {
                final CompletionStage<Optional<Packages>> packages;
                if (exists) {
                    packages = this.asto.value(key)
                        .thenApply(content -> (Packages) new JsonPackages(content))
                        .thenApply(Optional::of);
                } else {
                    packages = CompletableFuture.completedFuture(Optional.empty());
                }
                return packages;
            }
        );
    }
}
