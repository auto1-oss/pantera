/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.fs;

import com.artipie.asto.ArtipieIOException;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.ListResult;
import com.artipie.asto.Meta;
import com.artipie.asto.Storage;
import com.artipie.asto.UnderLockOperation;
import com.artipie.asto.ValueNotFoundException;
import com.artipie.asto.ext.CompletableFutureSupport;
import com.artipie.asto.lock.storage.StorageLock;
import com.artipie.asto.log.EcsLogger;
import com.artipie.asto.metrics.StorageMetricsCollector;
import hu.akarnokd.rxjava2.interop.CompletableInterop;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.vertx.core.file.CopyOptions;
import io.vertx.reactivex.RxHelper;
import io.vertx.reactivex.core.Vertx;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Simple storage, in files.
 * @since 0.1
 */
@SuppressWarnings("PMD.TooManyMethods")
public final class VertxFileStorage implements Storage {

    /**
     * Where we keep the data.
     */
    private final Path dir;

    /**
     * The Vert.x.
     */
    private final Vertx vertx;

    /**
     * Storage identifier string (name and path).
     */
    private final String id;

    /**
     * Ctor.
     *
     * @param path The path to the dir
     * @param vertx The Vert.x instance.
     */
    public VertxFileStorage(final Path path, final Vertx vertx) {
        this.dir = path;
        this.vertx = vertx;
        this.id = String.format("Vertx FS: %s", this.dir.toString());
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key key) {
        final long startNs = System.nanoTime();
        return Single.fromCallable(
            () -> {
                final Path path = this.path(key);
                return Files.exists(path) && !Files.isDirectory(path);
            }
        ).subscribeOn(RxHelper.blockingScheduler(this.vertx.getDelegate()))
            .to(SingleInterop.get()).toCompletableFuture()
            .whenComplete((result, throwable) -> {
                final long durationNs = System.nanoTime() - startNs;
                StorageMetricsCollector.record(
                    "exists",
                    durationNs,
                    throwable == null,
                    this.id
                );
            });
    }

    @Override
    public CompletableFuture<Collection<Key>> list(final Key prefix) {
        final long startNs = System.nanoTime();
        return Single.fromCallable(
            () -> {
                final Path path = this.path(prefix);
                final Collection<Key> keys;
                if (Files.exists(path)) {
                    final int dirnamelen;
                    if (Key.ROOT.equals(prefix)) {
                        dirnamelen = path.toString().length() + 1;
                    } else {
                        dirnamelen = path.toString().length() - prefix.string().length();
                    }
                    try {
                        keys = Files.walk(path)
                            .filter(Files::isRegularFile)
                            .map(Path::toString)
                            .map(p -> p.substring(dirnamelen))
                            .map(
                                s -> s.split(
                                    FileSystems.getDefault().getSeparator().replace("\\", "\\\\")
                                )
                            )
                            .map(Key.From::new)
                            .sorted(Comparator.comparing(Key.From::string))
                            .collect(Collectors.toList());
                    } catch (final IOException iex) {
                        throw new ArtipieIOException(iex);
                    }
                } else {
                    keys = Collections.emptyList();
                }
                EcsLogger.debug("com.artipie.asto")
                    .message("Found " + keys.size() + " objects by prefix: " + prefix.string())
                    .eventCategory("storage")
                    .eventAction("list_keys")
                    .eventOutcome("success")
                    .field("file.path", path.toString())
                    .field("file.directory", this.dir.toString())
                    .log();
                return keys;
            })
            .subscribeOn(RxHelper.blockingScheduler(this.vertx.getDelegate()))
            .to(SingleInterop.get()).toCompletableFuture()
            .whenComplete((result, throwable) -> {
                final long durationNs = System.nanoTime() - startNs;
                StorageMetricsCollector.record(
                    "list",
                    durationNs,
                    throwable == null,
                    this.id
                );
            });
    }

    @Override
    public CompletableFuture<ListResult> list(final Key prefix, final String delimiter) {
        final long startNs = System.nanoTime();
        return Single.fromCallable(
            () -> {
                final Path path = this.path(prefix);
                if (!Files.exists(path) || !Files.isDirectory(path)) {
                    return ListResult.EMPTY;
                }
                final Collection<Key> files = new ArrayList<>();
                final Collection<Key> directories = new LinkedHashSet<>();
                final String separator = FileSystems.getDefault().getSeparator();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                    for (final Path entry : stream) {
                        final String fileName = entry.getFileName().toString();
                        final Key entryKey;
                        if (Key.ROOT.equals(prefix) || prefix.string().isEmpty()) {
                            entryKey = new Key.From(
                                fileName.split(separator.replace("\\", "\\\\"))
                            );
                        } else {
                            final String[] prefixParts = prefix.string().split("/");
                            final String[] nameParts =
                                fileName.split(separator.replace("\\", "\\\\"));
                            final String[] combined =
                                new String[prefixParts.length + nameParts.length];
                            System.arraycopy(
                                prefixParts, 0, combined, 0, prefixParts.length
                            );
                            System.arraycopy(
                                nameParts, 0, combined, prefixParts.length, nameParts.length
                            );
                            entryKey = new Key.From(combined);
                        }
                        if (Files.isDirectory(entry)) {
                            final String dirKeyStr = entryKey.string().endsWith("/")
                                ? entryKey.string()
                                : entryKey.string() + "/";
                            directories.add(new Key.From(dirKeyStr));
                        } else if (Files.isRegularFile(entry)) {
                            files.add(entryKey);
                        }
                    }
                } catch (final IOException iex) {
                    throw new ArtipieIOException(iex);
                }
                EcsLogger.debug("com.artipie.asto")
                    .message(
                        "Hierarchical list for prefix '"
                            + prefix.string() + "' (" + files.size()
                            + " files, " + directories.size() + " directories)"
                    )
                    .eventCategory("storage")
                    .eventAction("list_hierarchical")
                    .eventOutcome("success")
                    .field("file.path", path.toString())
                    .field("file.directory", this.dir.toString())
                    .log();
                return new ListResult.Simple(files, new ArrayList<>(directories));
            })
            .subscribeOn(RxHelper.blockingScheduler(this.vertx.getDelegate()))
            .to(SingleInterop.get()).toCompletableFuture()
            .whenComplete((result, throwable) -> {
                final long durationNs = System.nanoTime() - startNs;
                StorageMetricsCollector.record(
                    "list_hierarchical",
                    durationNs,
                    throwable == null,
                    this.id
                );
            });
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        final long startNs = System.nanoTime();
        // Validate root key is not supported
        if (Key.ROOT.string().equals(key.string())) {
            return CompletableFuture.failedFuture(
                new ArtipieIOException("Unable to save to root")
            );
        }

        return Single.fromCallable(
            () -> {
                // Create temp file in .tmp directory at storage root to avoid filename length issues
                // Using parent directory could still exceed 255-byte limit if parent path is long
                final Path tmpDir = this.dir.resolve(".tmp");
                tmpDir.toFile().mkdirs();
                final Path tmp = tmpDir.resolve(UUID.randomUUID().toString());

                // Ensure target directory exists
                final Path target = this.path(key);
                final Path parent = target.getParent();
                if (parent != null) {
                    parent.toFile().mkdirs();
                }

                return tmp;
            })
            .subscribeOn(RxHelper.blockingScheduler(this.vertx.getDelegate()))
            .flatMapCompletable(
                tmp -> new VertxRxFile(
                    tmp,
                    this.vertx
                ).save(Flowable.fromPublisher(content))
                    .andThen(
                        this.vertx.fileSystem()
                            .rxMove(
                                tmp.toString(),
                                this.path(key).toString(),
                                new CopyOptions().setReplaceExisting(true)
                            )
                    )
                    .onErrorResumeNext(
                        throwable -> new VertxRxFile(tmp, this.vertx)
                            .delete()
                            .andThen(Completable.error(throwable))
                    )
            )
            .to(CompletableInterop.await())
            .<Void>thenApply(o -> null)
            .toCompletableFuture()
            .whenComplete((result, throwable) -> {
                final long durationNs = System.nanoTime() - startNs;
                final long sizeBytes = content.size().orElse(-1L);
                if (sizeBytes > 0) {
                    StorageMetricsCollector.record(
                        "save",
                        durationNs,
                        throwable == null,
                        this.id,
                        sizeBytes
                    );
                } else {
                    StorageMetricsCollector.record(
                        "save",
                        durationNs,
                        throwable == null,
                        this.id
                    );
                }
            });
    }

    @Override
    public CompletableFuture<Void> move(final Key source, final Key destination) {
        final long startNs = System.nanoTime();
        return Single.fromCallable(
            () -> {
                final Path dest = this.path(destination);
                dest.getParent().toFile().mkdirs();
                return dest;
            })
            .subscribeOn(RxHelper.blockingScheduler(this.vertx.getDelegate()))
            .flatMapCompletable(
                dest -> new VertxRxFile(this.path(source), this.vertx).move(dest)
            )
            .to(CompletableInterop.await())
            .<Void>thenApply(file -> null)
            .toCompletableFuture()
            .whenComplete((result, throwable) -> {
                final long durationNs = System.nanoTime() - startNs;
                StorageMetricsCollector.record(
                    "move",
                    durationNs,
                    throwable == null,
                    this.id
                );
            });
    }

    @Override
    public CompletableFuture<Void> delete(final Key key) {
        final long startNs = System.nanoTime();
        return new VertxRxFile(this.path(key), this.vertx)
            .delete()
            .to(CompletableInterop.await())
            .toCompletableFuture()
            .thenCompose(ignored -> CompletableFuture.allOf())
            .whenComplete((result, throwable) -> {
                final long durationNs = System.nanoTime() - startNs;
                StorageMetricsCollector.record(
                    "delete",
                    durationNs,
                    throwable == null,
                    this.id
                );
            });
    }

    @Override
    public CompletableFuture<Content> value(final Key key) {
        final long startNs = System.nanoTime();
        final CompletableFuture<Content> res;
        if (Key.ROOT.equals(key)) {
            res = new CompletableFutureSupport.Failed<Content>(
                new ArtipieIOException("Unable to load from root")
            ).get();
        } else {
            res = VertxFileStorage.size(this.path(key)).thenApply(
                size ->
                    new Content.OneTime(
                        new Content.From(
                            size,
                            new VertxRxFile(this.path(key), this.vertx).flow()
                        )
                    )
            );
        }
        return res.whenComplete((content, throwable) -> {
            final long durationNs = System.nanoTime() - startNs;
            if (content != null) {
                final long sizeBytes = content.size().orElse(-1L);
                if (sizeBytes > 0) {
                    StorageMetricsCollector.record(
                        "value",
                        durationNs,
                        throwable == null,
                        this.id,
                        sizeBytes
                    );
                } else {
                    StorageMetricsCollector.record(
                        "value",
                        durationNs,
                        throwable == null,
                        this.id
                    );
                }
            } else {
                StorageMetricsCollector.record(
                    "value",
                    durationNs,
                    throwable == null,
                    this.id
                );
            }
        });
    }

    @Override
    public <T> CompletionStage<T> exclusively(
        final Key key,
        final Function<Storage, CompletionStage<T>> operation
    ) {
        return new UnderLockOperation<>(new StorageLock(this, key), operation).perform(this);
    }

    @Deprecated
    @Override
    public CompletableFuture<Long> size(final Key key) {
        return VertxFileStorage.size(this.path(key));
    }

    @Override
    public CompletableFuture<? extends Meta> metadata(final Key key) {
        return CompletableFuture.completedFuture(Meta.EMPTY);
    }

    @Override
    public String identifier() {
        return this.id;
    }

    /**
     * Resolves key to file system path.
     *
     * @param key Key to be resolved to path.
     * @return Path created from key.
     */
    private Path path(final Key key) {
        return Paths.get(this.dir.toString(), key.string());
    }

    /**
     * File size.
     * @param path File path
     * @return Size
     */
    private static CompletableFuture<Long> size(final Path path) {
        return CompletableFuture.supplyAsync(
            () -> {
                try {
                    return Files.size(path);
                } catch (final NoSuchFileException fex) {
                    throw new ValueNotFoundException(Key.ROOT, fex);
                } catch (final IOException iex) {
                    throw new ArtipieIOException(iex);
                }
            }
        );
    }
}
