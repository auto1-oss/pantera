/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.rpm;

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Copy;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.asto.lock.Lock;
import com.auto1.pantera.asto.lock.storage.StorageLock;
import com.auto1.pantera.asto.misc.UncheckedIOScalar;
import com.auto1.pantera.asto.streams.ContentAsStream;
import com.auto1.pantera.rpm.asto.AstoChecksumAndName;
import com.auto1.pantera.rpm.asto.AstoRepoAdd;
import com.auto1.pantera.rpm.asto.AstoRepoRemove;
import com.auto1.pantera.rpm.http.RpmUpload;
import com.auto1.pantera.rpm.meta.XmlPackage;
import com.auto1.pantera.rpm.meta.XmlPrimaryChecksums;
import com.auto1.pantera.rpm.misc.PackagesDiff;
import hu.akarnokd.rxjava2.interop.CompletableInterop;
import io.reactivex.Completable;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * The RPM front.
 *
 * First, you make an instance of this class, providing
 * your storage as an argument:
 *
 * <pre> Rpm rpm = new Rpm(storage);</pre>
 *
 * Then, you put your binary RPM artifact to the storage and call
 * {@link Rpm#batchUpdate(Key)}. This method will parse the all RPM packages
 * in repository and update all the necessary meta-data files. Right after this,
 * your clients will be able to use the package, via {@code yum}:
 *
 * <pre> rpm.batchUpdate(new Key.From("rmp-repo"));</pre>
 *
 * @since 0.1
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class Rpm {

    /**
     * Primary storage.
     */
    private final Storage storage;

    /**
     * Repository configuration.
     */
    private final RepoConfig config;

    /**
     * New Rpm for repository in storage. Does not include filelists.xml in update.
     * @param stg The storage which contains repository
     */
    public Rpm(final Storage stg) {
        this(stg, StandardNamingPolicy.PLAIN, Digest.SHA256, false);
    }

    /**
     * New Rpm for repository in storage.
     * @param stg The storage which contains repository
     * @param filelists Include file lists in update
     */
    public Rpm(final Storage stg, final boolean filelists) {
        this(stg, StandardNamingPolicy.PLAIN, Digest.SHA256, filelists);
    }

    /**
     * Ctor.
     * @param stg The storage
     * @param naming RPM files naming policy
     * @param dgst Hashing sum computation algorithm
     * @param filelists Include file lists in update
     */
    public Rpm(final Storage stg, final NamingPolicy naming, final Digest dgst,
        final boolean filelists) {
        this(stg, new RepoConfig.Simple(dgst, naming, filelists));
    }

    /**
     * Ctor.
     * @param storage The storage
     * @param config Repository configuration
     */
    public Rpm(final Storage storage, final RepoConfig config) {
        this.storage = storage;
        this.config = config;
    }

    /**
     * Batch update RPM files for repository.
     * @param prefix Repository key prefix
     * @return Completable action
     * @throws PanteraIOException On IO-operation errors
     */
    public Completable batchUpdate(final Key prefix) {
        return this.doWithLock(
            prefix,
            () -> Completable.fromFuture(this.calcDiff(prefix).thenCompose(
                list -> {
                    final Storage sub = new SubStorage(prefix, this.storage);
                    return new AstoRepoAdd(sub, this.config).perform().thenCompose(
                        nothing -> new AstoRepoRemove(sub, this.config).perform(list)
                    );
                }).toCompletableFuture()
            )
        );
    }

    /**
     * Performs operation under root lock with one hour expiration time.
     *
     * @param target Lock target key.
     * @param operation Operation.
     * @return Completion of operation and lock.
     */
    private Completable doWithLock(final Key target, final Supplier<Completable> operation) {
        final Lock lock = new StorageLock(
            this.storage,
            target,
            Instant.now().plus(Duration.ofHours(1))
        );
        return Completable.fromFuture(
            lock.acquire()
                .thenCompose(nothing -> operation.get().to(CompletableInterop.await()))
                .thenCompose(nothing -> lock.release())
                .toCompletableFuture()
        );
    }

    /**
     * Calculate differences between current metadata and storage rpms, prepare
     * packages to add or to remove.
     * @param prefix Prefix key
     * @return Completable action with list of the checksums of the remove packages
     */
    private CompletionStage<Collection<String>> calcDiff(final Key prefix) {
        return this.storage.list(new Key.From(prefix, "repodata"))
            .thenApply(
                list -> list.stream().filter(
                    item -> item.string().contains(XmlPackage.PRIMARY.lowercase())
                        && item.string().endsWith("xml.gz")
                ).findFirst()
            ).thenCompose(
                opt -> {
                    final CompletionStage<Collection<String>> res;
                    final SubStorage sub = new SubStorage(prefix, this.storage);
                    if (opt.isPresent()) {
                        res = this.storage.value(opt.get()).thenCompose(
                            val -> new ContentAsStream<Map<String, String>>(val).process(
                                input -> new XmlPrimaryChecksums(
                                    new UncheckedIOScalar<>(() -> new GZIPInputStream(input))
                                        .value()
                                ).read()
                            )
                        ).thenCompose(
                            primary -> new AstoChecksumAndName(this.storage, this.config.digest())
                                .calculate(prefix)
                                .thenApply(repo -> new PackagesDiff(primary, repo))
                        ).thenCompose(
                            diff -> Rpm.copyPackagesToAdd(
                                sub,
                                diff.toAdd().stream().map(Key.From::new)
                                    .collect(Collectors.toList())
                            ).thenApply(nothing -> diff.toDelete().values())
                        );
                    } else {
                        res = sub.list(Key.ROOT).thenApply(
                            list -> list.stream().filter(item -> item.string().endsWith("rpm"))
                        ).thenCompose(
                            rpms -> copyPackagesToAdd(sub, rpms.collect(Collectors.toList()))
                        ).thenApply(nothing -> Collections.emptySet());
                    }
                    return res;
                }
            );
    }

    /**
     * Handles packages that should be added to metadata.
     * @param asto Storage
     * @param rpms Packages
     * @return Completable action
     */
    private static CompletableFuture<Void> copyPackagesToAdd(
        final Storage asto, final List<Key> rpms
    ) {
        return new Copy(asto, rpms).copy(new SubStorage(RpmUpload.TO_ADD, asto));
    }
}
