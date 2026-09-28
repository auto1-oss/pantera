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
import com.auto1.pantera.asto.lock.storage.IndexUpdateLock;
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
import java.util.regex.Pattern;
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
     * Key every index writer (upload, delete, import) locks while it
     * regenerates the index. The importer has always locked this key.
     */
    private static final Key INDEX_LOCK = new Key.From("specs.4.8.gz");

    /**
     * Gems directory.
     */
    private static final String GEMS = "gems";

    /**
     * Quick specs directory.
     */
    private static final Key QUICK = new Key.From("quick", "Marshal.4.8");

    /**
     * Temporary key of an upload ({@code gems/<uuid without dashes>.gem}).
     */
    private static final Pattern UPLOAD = Pattern.compile("gems/[0-9a-f]{32}\\.gem");

    /**
     * Ruby runtime shared by every reindex after a delete, so a delete does
     * not start a runtime of its own.
     */
    private static final SharedRuntime REINDEX = new SharedRuntime();

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
        return new IndexUpdateLock(this.storage, Gem.INDEX_LOCK).run(
            locked -> this.indexed(gem)
        );
    }

    /**
     * Rebuild the index from the gems left in storage, after gems were
     * removed from it (management-API delete). {@code specs.4.8},
     * {@code latest_specs.4.8} (the highest remaining version of each gem),
     * {@code prerelease_specs.4.8} and their {@code .gz} variants are
     * regenerated, and the {@code quick/Marshal.4.8} spec of every gem that
     * is gone is removed. Runs under the same index lock as {@link #update}.
     *
     * @return Completable action
     */
    public CompletionStage<Void> reindex() {
        return new IndexUpdateLock(this.storage, Gem.INDEX_LOCK).run(
            locked -> newTempDir().thenCompose(
                tmp -> new Copy(this.storage, Gem::stored).copy(new FileStorage(tmp))
                    .thenCompose(
                        ignore -> CompletableFuture.supplyAsync(
                            new UncheckedSupplier<>(
                                () -> Files.createDirectories(tmp.resolve(Gem.GEMS))
                            )
                        )
                    )
                    .thenCompose(
                        dir -> Gem.REINDEX.apply(RubyGemIndex::new).thenAccept(
                            // The indexer only uses the directory of the
                            // path it is given.
                            index -> index.update(dir.resolve("reindex.gem"))
                        )
                    )
                    .thenCompose(
                        ignored -> new Copy(new FileStorage(tmp), key -> !Gem.isGem(key))
                            .copy(this.storage)
                    )
                    .thenCompose(ignored -> this.dropStaleQuickSpecs(tmp))
                    .handle(removeTempDir(tmp))
            )
        );
    }

    /**
     * Index an uploaded gem; the caller holds the index lock.
     * @param gem Uploaded gem key
     * @return Name and version of the gem
     */
    private CompletionStage<Pair<String, String>> indexed(final Key gem) {
        return newTempDir().thenCompose(
            tmp -> new Copy(
                this.storage, key -> Gem.stored(key) || key.equals(gem)
            ).copy(new FileStorage(tmp)).thenCompose(
                ignore -> this.shared.apply(RubyGemMeta::new)
                    .thenApply(meta -> Gem.read(meta, tmp.resolve(gem.string())))
                    .thenCompose(
                        info -> {
                            final RevisionFormat fmt = new RevisionFormat();
                            final String name = Gem.fileName(info, fmt);
                            final Path dir = gem.parent()
                                .map(key -> tmp.resolve(key.string())).orElse(tmp);
                            final Key stored = gem.parent()
                                .<Key>map(key -> new Key.From(key, name))
                                .orElseGet(() -> new Key.From(name));
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
                                    // Only the new gem and the index go back:
                                    // the other gems are unchanged, and
                                    // writing the snapshot back would
                                    // resurrect a gem deleted meanwhile.
                                    ignored -> new Copy(
                                        new FileStorage(tmp),
                                        key -> !Gem.isGem(key) || key.equals(stored)
                                    ).copy(this.storage)
                                ).thenApply(ignored -> new ImmutablePair<>(fmt.name, fmt.version));
                        }
                    )
            ).handle(removeTempDir(tmp))
        );
    }

    /**
     * Remove the quick specs of gems that are no longer in storage.
     * @param tmp Reindexed snapshot, holding the quick specs of every gem left
     * @return Completable action
     */
    private CompletableFuture<Void> dropStaleQuickSpecs(final Path tmp) {
        final FileStorage fresh = new FileStorage(tmp);
        return fresh.list(Gem.QUICK).thenCompose(
            kept -> this.storage.list(Gem.QUICK).thenCompose(
                stored -> CompletableFuture.allOf(
                    stored.stream()
                        .filter(key -> !kept.contains(key))
                        .map(this.storage::delete)
                        .toArray(CompletableFuture[]::new)
                )
            )
        );
    }

    /**
     * Whether a key is a gem file.
     * @param key Key
     * @return True for a {@code .gem}
     */
    private static boolean isGem(final Key key) {
        return key.string().endsWith(".gem");
    }

    /**
     * Whether a key is a stored gem that belongs in the index: a {@code .gem}
     * that is not the temporary key of an upload still being indexed.
     * @param key Key
     * @return True for an indexed gem
     */
    private static boolean stored(final Key key) {
        return Gem.isGem(key) && !Gem.UPLOAD.matcher(key.string()).matches();
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
            throw new InvalidGemException(
                "The upload is not a valid gem: its file name escapes the indexing directory",
                null
            );
        }
        return dest;
    }

    /**
     * Read the specification of an uploaded gem.
     * @param meta Gem metadata reader
     * @param path Uploaded gem
     * @return Gem specification
     * @throws InvalidGemException If the file cannot be read as a gem
     */
    private static GemMeta.MetaInfo read(final GemMeta meta, final Path path) {
        try {
            return meta.info(path);
        } catch (final RuntimeException ex) {
            // RubyGems raises Gem::Package::FormatError & co. for a truncated
            // or foreign file; the upload is the client's error, not ours.
            // Its message names the server-side temporary path, so the
            // client only gets the verdict.
            throw new InvalidGemException(
                "The upload is not a valid gem: it cannot be read as a gem package", ex
            );
        }
    }

    /**
     * File name ({@code <name>-<version>}) a gem is stored under.
     * @param info Gem specification
     * @param fmt Revision format collecting name and version
     * @return File name
     * @throws InvalidGemException If the specification lacks a name or version
     */
    private static String fileName(final GemMeta.MetaInfo info, final RevisionFormat fmt) {
        final String res;
        try {
            res = info.toString(fmt);
        } catch (final RuntimeException ex) {
            throw new InvalidGemException(
                "The upload is not a valid gem: its specification cannot be read", ex
            );
        }
        if (fmt.name == null || fmt.version == null) {
            throw new InvalidGemException(
                "The upload is not a valid gem: its specification has no name or version",
                null
            );
        }
        return res;
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
