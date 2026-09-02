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
package com.auto1.pantera.gem;

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Copy;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.misc.UncheckedSupplier;
import com.auto1.pantera.gem.GemMeta.MetaInfo;
import com.auto1.pantera.gem.ruby.RubyGemDependencies;
import com.auto1.pantera.gem.ruby.RubyGemIndex;
import com.auto1.pantera.gem.ruby.RubyGemMeta;
import com.auto1.pantera.gem.ruby.SharedRuntime;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

/**
 * An SDK, which servers gem packages.
 * <p>
 * Performes gem index update using specified indexer implementation.
 * </p>
 * @since 1.0
 */
public final class Gem {

    /**
     * Gem repository storage.
     */
    private final Storage storage;

    /**
     * Shared ruby runtime.
     */
    private final SharedRuntime shared;

    /**
     * New Gem SDK with default indexer.
     * @param storage Repository storage.
     */
    public Gem(final Storage storage) {
        this.storage = storage;
        this.shared = new SharedRuntime();
    }

    /**
     * Batch update Ruby gems for repository.
     *
     * <p>SECURITY (2.2.9): the snapshot handed to the indexer contains every
     * stored {@code .gem} plus the gem being indexed — and deliberately NOT
     * the stored {@code specs.4.8} / {@code latest_specs.4.8} index blobs.
     * Those blobs are repository-writable, and the previous implementation
     * {@code Marshal.load}-ed them to merge the new entry, which let a
     * planted stream drive arbitrary class instantiation in the JRuby
     * runtime. The index is now rebuilt from the trusted gem specs, which
     * also means every {@code quick/} spec is regenerated consistently. The
     * cost is copying all gems per update rather than one; correctness of
     * the index no longer depends on bytes an attacker can write.</p>
     *
     * @param gem Ruby gem for indexing
     * @return Completable action
     */
    public CompletionStage<Pair<String, String>> update(final Key gem) {
        return newTempDir().thenCompose(
            tmp -> new Copy(
                this.storage, key -> key.string().endsWith(".gem") || key.equals(gem)
            ).copy(new FileStorage(tmp)).thenCompose(
                ignore -> this.shared.apply(RubyGemMeta::new)
                    .thenApply(meta -> meta.info(tmp.resolve(gem.string())))
                    .thenCompose(
                        info -> {
                            final RevisionFormat fmt = new RevisionFormat();
                            final String name = info.toString(fmt);
                            final Path dir = gem.parent()
                                .map(key -> tmp.resolve(key.string())).orElse(tmp);
                            return CompletableFuture.supplyAsync(
                                new UncheckedSupplier<>(
                                    () -> Files.move(
                                        tmp.resolve(gem.string()),
                                        contained(dir, name),
                                        StandardCopyOption.REPLACE_EXISTING
                                    )
                                )
                            ).thenCompose(
                                path -> this.shared.apply(RubyGemIndex::new)
                                    .thenAccept(index -> index.update(path))
                                ).thenCompose(
                                    ignored -> new Copy(new FileStorage(tmp)).copy(this.storage)
                                ).thenApply(ignored -> new ImmutablePair<>(fmt.name, fmt.version));
                        }
                    )
            ).handle(removeTempDir(tmp))
        );
    }

    /**
     * Gem info data.
     * @param gem Gem name
     * @return Future
     */
    public CompletionStage<MetaInfo> info(final String gem) {
        return newTempDir().thenCompose(
            tmp -> new Copy(this.storage, new GemKeyPredicate(gem))
                .copy(new FileStorage(tmp))
                .thenApply(ignore -> tmp)
        ).thenCompose(
            tmp -> this.shared.apply(RubyGemMeta::new)
                .thenCompose(
                    info -> new FileStorage(tmp).list(Key.ROOT).thenApply(
                        items -> items.stream().findFirst()
                            .map(first -> Paths.get(tmp.toString(), first.string()))
                            .map(path -> info.info(path))
                            .orElseThrow(() -> new PanteraIOException("gem not found"))
                    )
                ).handle(removeTempDir(tmp))
        );
    }

    /**
     * Retreive and merge dependencies for gems specified.
     * @param gems Set of gem names
     * @return Dependencies binary data
     */
    public CompletionStage<ByteBuffer> dependencies(final Set<? extends String> gems) {
        return newTempDir().thenCompose(
            tmp -> new Copy(
                this.storage, new GemKeyPredicate(gems)
            ).copy(new FileStorage(tmp)).thenCompose(
                ignore -> this.shared.apply(RubyGemDependencies::new).thenCompose(
                    deps -> new FileStorage(tmp).list(Key.ROOT).thenApply(
                        keys -> keys.stream()
                            .map(key -> tmp.resolve(key.string()))
                            .collect(Collectors.toSet())
                    ).thenApply(paths -> new ImmutablePair<>(deps, paths))
                ).thenApply(
                    tuple -> tuple.getLeft().dependencies(tuple.getRight())
                )
            ).handle(removeTempDir(tmp))
        );
    }

    /**
     * Resolve a gem file name inside its directory and refuse anything that
     * would leave it.
     *
     * <p>SECURITY (2.2.9): the name is {@code <name>-<version>.gem} built from
     * the uploaded gem's OWN spec. RubyGems validates spec names only when
     * building a gem, not when reading one, so an uploader who builds with
     * validation skipped can ship a spec named {@code ../../x}; without this
     * check {@code Files.move} wrote the blob to a host path of the
     * uploader's choosing. A single plain file name is the only accepted
     * shape: no path separators, no parent segments, and the normalised
     * result must stay under {@code dir}.</p>
     *
     * @param dir Directory the gem must land in
     * @param name Spec-derived file name
     * @return The contained destination path
     */
    private static Path contained(final Path dir, final String name) {
        final Path root = dir.toAbsolutePath().normalize();
        final Path dest = root.resolve(name).normalize();
        if (name.isEmpty()
            || name.indexOf('/') >= 0
            || name.indexOf('\\') >= 0
            || !dest.startsWith(root)
            || !root.equals(dest.getParent())) {
            throw new PanteraIOException(
                "Gem file name escapes the indexing directory: " + name
            );
        }
        return dest;
    }

    /**
     * Create new temp dir asynchronously.
     * @return Future
     */
    private static CompletionStage<Path> newTempDir() {
        return CompletableFuture.supplyAsync(
            new UncheckedSupplier<>(
                () -> {
                    final Path tmp = Files.createTempDirectory(Gem.class.getSimpleName());
                    tmp.toFile().deleteOnExit();
                    return tmp;
                }
            )
        );
    }

    /**
     * Handle async result.
     * @param tmpdir Path directory to remove
     * @param <T> Result type
     * @return Function handler
     */
    private static <T> BiFunction<T, Throwable, T> removeTempDir(
        final Path tmpdir) {
        return (res, err) -> {
            try {
                if (tmpdir != null) {
                    FileUtils.deleteDirectory(new File(tmpdir.toString()));
                }
            } catch (final IOException iox) {
                throw new PanteraIOException(iox);
            }
            if (err != null) {
                throw new CompletionException(err);
            }
            return res;
        };
    }

    /**
     * Revision Gem meta format.
     * @since 1.0
     */
    private static final class RevisionFormat implements GemMeta.MetaFormat {

        /**
         * Gem name.
         */
        private String name;

        /**
         * Gem value.
         */
        private String version;

        @Override
        public void print(final String nme, final String value) {
            if ("name".equals(nme)) {
                this.name = value;
            }
            if ("version".equals(nme)) {
                this.version = value;
            }
        }

        @Override
        public void print(final String nme, final MetaInfo value) {
            // do nothing
        }

        @Override
        public void print(final String nme, final String[] values) {
            // do nothing
        }

        @Override
        public String toString() {
            return String.format("%s-%s.gem", this.name, this.version);
        }
    }
}
