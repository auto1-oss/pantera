/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.fs;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.OneTimePublisher;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.UnderLockOperation;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.asto.ext.CompletableFutureSupport;
import com.auto1.pantera.asto.lock.storage.StorageLock;
import com.auto1.pantera.asto.log.EcsLogger;
import com.auto1.pantera.asto.metrics.StorageMetricsCollector;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
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
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.cqfn.rio.file.File;

/**
 * Simple storage, in files.
 *
 * @since 0.1
 */
@SuppressWarnings("PMD.TooManyMethods")
public final class FileStorage implements Storage {

    /**
     * Where we keep the data.
     */
    private final Path dir;

    /**
     * Storage string identifier (name and path).
     */
    private final String id;

    /**
     * Ctor.
     * @param path The path to the dir
     * @param nothing Just for compatibility
     * @deprecated Use {@link FileStorage#FileStorage(Path)} ctor instead.
     */
    @Deprecated
    @SuppressWarnings("PMD.UnusedFormalParameter")
    public FileStorage(final Path path, final Object nothing) {
        this(path);
    }

    /**
     * Ctor.
     * @param path The path to the dir
     */
    public FileStorage(final Path path) {
        this.dir = path;
        this.id = String.format("FS: %s", this.dir.toString());
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key key) {
        final long startNs = System.nanoTime();
        return this.keyPath(key).thenApplyAsync(
            path -> Files.exists(path) && !Files.isDirectory(path)
        ).whenComplete((result, throwable) -> {
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
        return this.keyPath(prefix).thenApplyAsync(
            path -> {
                Collection<Key> keys;
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
                    } catch (final NoSuchFileException nsfe) {
                        // Handle race condition: directory was deleted between exists() check and walk()
                        // Treat as empty directory to avoid breaking callers
                        EcsLogger.debug("com.auto1.pantera.asto")
                            .message("Directory disappeared during list operation")
                            .eventCategory("storage")
                            .eventAction("list_keys")
                            .eventOutcome("success")
                            .field("file.path", path.toString())
                            .log();
                        keys = Collections.emptyList();
                    } catch (final IOException iex) {
                        throw new PanteraIOException(iex);
                    }
                } else {
                    keys = Collections.emptyList();
                }
                EcsLogger.debug("com.auto1.pantera.asto")
                    .message("Found " + keys.size() + " objects by prefix: " + prefix.string())
                    .eventCategory("storage")
                    .eventAction("list_keys")
                    .eventOutcome("success")
                    .field("file.path", path.toString())
                    .field("file.directory", this.dir.toString())
                    .log();
                return keys;
            }
        ).whenComplete((result, throwable) -> {
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
        return this.keyPath(prefix).thenApplyAsync(
            path -> {
                if (!Files.exists(path)) {
                    EcsLogger.debug("com.auto1.pantera.asto")
                        .message("Path does not exist for prefix: " + prefix.string())
                        .eventCategory("storage")
                        .eventAction("list_hierarchical")
                        .eventOutcome("success")
                        .field("file.path", path.toString())
                        .log();
                    return ListResult.EMPTY;
                }

                if (!Files.isDirectory(path)) {
                    EcsLogger.debug("com.auto1.pantera.asto")
                        .message("Path is not a directory for prefix: " + prefix.string())
                        .eventCategory("storage")
                        .eventAction("list_hierarchical")
                        .eventOutcome("success")
                        .field("file.path", path.toString())
                        .log();
                    return ListResult.EMPTY;
                }
                
                final Collection<Key> files = new ArrayList<>();
                final Collection<Key> directories = new LinkedHashSet<>();
                final String separator = FileSystems.getDefault().getSeparator();
                
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                    for (final Path entry : stream) {
                        final String fileName = entry.getFileName().toString();
                        
                        // Build the key relative to storage root
                        final Key entryKey;
                        if (Key.ROOT.equals(prefix) || prefix.string().isEmpty()) {
                            entryKey = new Key.From(fileName.split(separator.replace("\\", "\\\\")));
                        } else {
                            final String[] prefixParts = prefix.string().split("/");
                            final String[] nameParts = fileName.split(separator.replace("\\", "\\\\"));
                            final String[] combined = new String[prefixParts.length + nameParts.length];
                            System.arraycopy(prefixParts, 0, combined, 0, prefixParts.length);
                            System.arraycopy(nameParts, 0, combined, prefixParts.length, nameParts.length);
                            entryKey = new Key.From(combined);
                        }
                        
                        if (Files.isDirectory(entry)) {
                            // Add trailing delimiter to indicate directory
                            final String dirKeyStr = entryKey.string().endsWith("/") 
                                ? entryKey.string() 
                                : entryKey.string() + "/";
                            directories.add(new Key.From(dirKeyStr));
                        } else if (Files.isRegularFile(entry)) {
                            files.add(entryKey);
                        }
                    }
                } catch (final IOException iex) {
                    throw new PanteraIOException(iex);
                }

                EcsLogger.debug("com.auto1.pantera.asto")
                    .message("Hierarchical list completed for prefix '" + prefix.string() + "' (" + files.size() + " files, " + directories.size() + " directories)")
                    .eventCategory("storage")
                    .eventAction("list_hierarchical")
                    .eventOutcome("success")
                    .log();

                return new ListResult.Simple(files, new ArrayList<>(directories));
            }
        );
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        final long startNs = System.nanoTime();
        // Validate root key is not supported
        if (Key.ROOT.string().equals(key.string())) {
            return new CompletableFutureSupport.Failed<Void>(
                new PanteraIOException("Unable to save to root")
            ).get();
        }

        final CompletableFuture<Void> result = this.keyPath(key).thenApplyAsync(
            path ->  {
                // Create temp file in .tmp directory at storage root to avoid filename length issues
                // Using parent directory could still exceed 255-byte limit if parent path is long
                final Path tmpDir = this.dir.resolve(".tmp");
                try {
                    Files.createDirectories(tmpDir);
                } catch (final IOException iex) {
                    throw new PanteraIOException(iex);
                }
                final Path tmp = tmpDir.resolve(UUID.randomUUID().toString());

                // Ensure target directory exists
                final Path parent = path.getParent();
                if (parent != null) {
                    try {
                        Files.createDirectories(parent);
                    } catch (final IOException iex) {
                        throw new PanteraIOException(iex);
                    }
                }

                return ImmutablePair.of(path, tmp);
            }
        ).thenCompose(
            pair -> {
                final Path path = pair.getKey();
                final Path tmp = pair.getValue();
                return new File(tmp).write(
                    new OneTimePublisher<>(content),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).thenCompose(
                    nothing -> FileStorage.move(tmp, path)
                ).handleAsync(
                    (nothing, throwable) -> {
                        tmp.toFile().delete();
                        if (throwable == null) {
                            return null;
                        } else {
                            throw new PanteraIOException(throwable);
                        }
                    }
                );
            }
        );
        return result.whenComplete((res, throwable) -> {
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
        return this.keyPath(source).thenCompose(
            src -> this.keyPath(destination).thenApply(dst -> ImmutablePair.of(src, dst))
        ).thenCompose(pair -> FileStorage.move(pair.getKey(), pair.getValue()))
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
    @SuppressWarnings("PMD.ExceptionAsFlowControl")
    public CompletableFuture<Void> delete(final Key key) {
        final long startNs = System.nanoTime();
        return this.keyPath(key).thenAcceptAsync(
            path -> {
                if (Files.exists(path) && !Files.isDirectory(path)) {
                    try {
                        Files.delete(path);
                        this.deleteEmptyParts(path.getParent());
                    } catch (final IOException iex) {
                        throw new PanteraIOException(iex);
                    }
                } else {
                    throw new ValueNotFoundException(key);
                }
            }
        ).whenComplete((result, throwable) -> {
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
    public CompletableFuture<? extends Meta> metadata(final Key key) {
        return this.keyPath(key).thenApplyAsync(
            path -> {
                final BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(path, BasicFileAttributes.class);
                } catch (final NoSuchFileException fex) {
                    throw new ValueNotFoundException(key, fex);
                } catch (final IOException iox) {
                    throw new PanteraIOException(iox);
                }
                return new FileMeta(attrs);
            }
        );
    }

    @Override
    public CompletableFuture<Content> value(final Key key) {
        final long startNs = System.nanoTime();
        final CompletableFuture<Content> res;
        if (Key.ROOT.string().equals(key.string())) {
            res = new CompletableFutureSupport.Failed<Content>(
                new PanteraIOException("Unable to load from root")
            ).get();
        } else {
            res = this.metadata(key).thenApply(
                meta -> meta.read(Meta.OP_SIZE).orElseThrow(
                    () -> new PanteraException(
                        String.format("Size is not available for '%s' key", key.string())
                    )
                )
            ).thenCompose(
                size -> this.keyPath(key).thenApply(path -> ImmutablePair.of(path, size))
            ).thenApply(
                pair -> new Content.OneTime(
                    new Content.From(pair.getValue(), new File(pair.getKey()).content())
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

    @Override
    public String identifier() {
        return this.id;
    }

    /**
     * Removes empty key parts (directories).
     * Also cleans up the .tmp directory if it's empty.
     * @param target Directory path
     */
    private void deleteEmptyParts(final Path target) {
        final Path dirabs = this.dir.normalize().toAbsolutePath();
        final Path path = target.normalize().toAbsolutePath();
        if (!path.toString().startsWith(dirabs.toString()) || dirabs.equals(path)) {
            // Clean up .tmp directory if it's empty
            this.cleanupTmpDir();
            return;
        }
        if (Files.isDirectory(path)) {
            boolean again = false;
            try {
                try (Stream<Path> files = Files.list(path)) {
                    if (!files.findFirst().isPresent()) {
                        Files.deleteIfExists(path);
                        again = true;
                    }
                }
                if (again) {
                    this.deleteEmptyParts(path.getParent());
                }
            } catch (final NoSuchFileException ex) {
                this.deleteEmptyParts(path.getParent());
            }
            catch (final IOException err) {
                throw new PanteraIOException(err);
            }
        }
    }

    /**
     * Cleans up the .tmp directory if it exists and is empty.
     */
    private void cleanupTmpDir() {
        final Path tmpDir = this.dir.resolve(".tmp");
        if (Files.exists(tmpDir) && Files.isDirectory(tmpDir)) {
            try (Stream<Path> files = Files.list(tmpDir)) {
                if (!files.findFirst().isPresent()) {
                    Files.deleteIfExists(tmpDir);
                }
            } catch (final IOException ignore) {
                // Ignore cleanup errors
            }
        }
    }

    /**
     * Moves file from source path to destination.
     *
     * @param source Source path.
     * @param dest Destination path.
     * @return Completion of moving file.
     */
    private static CompletableFuture<Void> move(final Path source, final Path dest) {
        return CompletableFuture.supplyAsync(
            () -> {
                try {
                    Files.createDirectories(dest.getParent());
                } catch (final IOException iex) {
                    throw new PanteraIOException(iex);
                }
                return dest;
            }
        ).thenAcceptAsync(
            dst -> {
                try {
                    Files.move(source, dst, StandardCopyOption.REPLACE_EXISTING);
                } catch (final java.nio.file.NoSuchFileException nfe) {
                    // Retry once: parent dir may have been removed by concurrent operation
                    try {
                        Files.createDirectories(dst.getParent());
                        Files.move(source, dst, StandardCopyOption.REPLACE_EXISTING);
                    } catch (final IOException retry) {
                        retry.addSuppressed(nfe);
                        throw new PanteraIOException(retry);
                    }
                } catch (final IOException iex) {
                    throw new PanteraIOException(iex);
                }
            }
        );
    }

    /**
     * Converts key to path.
     * <p>
     * Validates the path is in storage directory and converts it to path.
     * Fails with {@link PanteraIOException} if key is out of storage location.
     * </p>
     *
     * @param key Key to validate.
     * @return Path future
     */
    private CompletableFuture<? extends Path> keyPath(final Key key) {
        final Path path = this.dir.resolve(key.string());
        final CompletableFuture<Path> res = new CompletableFuture<>();
        if (path.normalize().startsWith(path)) {
            res.complete(path);
        } else {
            res.completeExceptionally(
                new PanteraIOException(
                    String.format("Entry path is out of storage: %s", key)
                )
            );
        }
        return res;
    }
}
