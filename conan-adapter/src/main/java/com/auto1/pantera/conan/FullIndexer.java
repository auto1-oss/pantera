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
package  com.auto1.pantera.conan;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.rx.RxFuture;
import hu.akarnokd.rxjava2.interop.FlowableInterop;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import io.reactivex.schedulers.Schedulers;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * Conan V2 API revisions index (re)generation support.
 * Revisions index stored in revisions.txt file in json format.
 * There are 2+ index files: recipe revisions and binary revisions (per package).
 * @since 0.1
 */
public class FullIndexer {

    /**
     * Package recipe (sources) subdir name.
     */
    private static final String SRC_SUBDIR = "export";

    /**
     * Package binaries subdir name.
     */
    private static final String BIN_SUBDIR = "package";

    /**
     * Current Pantera storage instance.
     */
    private final Storage storage;

    /**
     * Revision info indexer.
     */
    private final RevisionsIndexer indexer;

    /**
     * Initializes instance of indexer.
     * @param storage Current Pantera storage instance.
     * @param indexer Revision info indexer.
     */
    public FullIndexer(final Storage storage, final RevisionsIndexer indexer) {
        this.storage = storage;
        this.indexer = indexer;
    }

    /**
     * Updates binary index file. Fully recursive.
     * Does updateRecipeIndex(), then for each revision & for each pkg binary updateBinaryIndex().
     * @param key Key in the Pantera Storage for the revisions index file.
     * @return CompletionStage to handle operation completion.
     */
    public CompletionStage<Void> fullIndexUpdate(final Key key) {
        // Use non-blocking RxFuture.single instead of blocking SingleInterop.fromFuture
        final Flowable<List<Integer>> flowable = RxFuture.single(
            this.indexer.buildIndex(
                key, PackageList.PKG_SRC_LIST, (name, rev) -> new Key.From(
                    key, rev.toString(), FullIndexer.SRC_SUBDIR, name
                )
            )).flatMapPublisher(Flowable::fromIterable).flatMap(
                rev -> {
                    final Key packages = new Key.From(
                        key, rev.toString(), FullIndexer.BIN_SUBDIR
                    );
                    return RxFuture.single(
                        new PackageList(this.storage).get(packages).thenApply(
                            pkgs -> pkgs.stream().map(
                                pkg -> new Key.From(packages, pkg)
                            ).collect(Collectors.toList())
                        )
                    ).flatMapPublisher(Flowable::fromIterable);
                })
            .flatMap(
                pkgkey -> FlowableInterop.fromFuture(
                    this.indexer.buildIndex(
                        pkgkey, PackageList.PKG_BIN_LIST, (name, rev) ->
                            new Key.From(pkgkey, rev.toString(), name)
                    )
                )
            )
            .parallel().runOn(Schedulers.io())
            // CRITICAL: Do NOT use observeOn() after sequential() - causes backpressure violations
            // The parallel().runOn() already handles threading, sequential() just merges results
            // Adding observeOn() here creates unnecessary thread switching and buffer overflow
            .sequential();
        return flowable.toList().to(SingleInterop.get()).thenCompose(
            unused -> CompletableFuture.completedFuture(null)
        );
    }
}
