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
package com.auto1.pantera.repojob;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * In-memory {@link PurgeStore} recording what the purge asked of it, with
 * hooks to fail, throttle, or write behind the purge's back.
 *
 * @since 2.2.14
 */
final class FakePurgeStore implements PurgeStore {

    /**
     * Object names.
     */
    private final TreeSet<String> names = new TreeSet<>();

    /**
     * Resume positions the purge listed from.
     */
    private final List<String> listed = new ArrayList<>();

    /**
     * Sizes of the delete batches.
     */
    private final List<Integer> batches = new ArrayList<>();

    /**
     * Throttled answers still to give.
     */
    private final AtomicInteger throttles = new AtomicInteger();

    /**
     * Delete calls still to succeed before one fails; negative for never.
     */
    private final AtomicInteger failAfter = new AtomicInteger(-1);

    /**
     * Uploads aborted.
     */
    private final AtomicInteger aborted = new AtomicInteger();

    /**
     * Runs after every delete batch (a writer racing the purge).
     */
    private Consumer<FakePurgeStore> afterDelete = store -> { };

    /**
     * Add objects.
     * @param dir Directory
     * @param count How many
     * @return This store
     */
    FakePurgeStore fill(final String dir, final int count) {
        for (int idx = 0; idx < count; idx += 1) {
            this.names.add(String.format("%s/pkg/%06d.bin", dir, idx));
        }
        return this;
    }

    /**
     * Add one object.
     * @param name Object name
     */
    void put(final String name) {
        this.names.add(name);
    }

    /**
     * Answer the next calls with "slow down".
     * @param times How many calls
     */
    void throttle(final int times) {
        this.throttles.set(times);
    }

    /**
     * Fail a delete after the given number of successful ones.
     * @param batchesOk Successful delete batches before the failure
     */
    void failDeleteAfter(final int batchesOk) {
        this.failAfter.set(batchesOk);
    }

    /**
     * Run something after every delete batch.
     * @param hook Hook
     */
    void afterDelete(final Consumer<FakePurgeStore> hook) {
        this.afterDelete = hook;
    }

    /**
     * Objects left.
     * @return Names
     */
    List<String> left() {
        return new ArrayList<>(this.names);
    }

    /**
     * Resume positions the purge listed from ({@code ""} for the beginning).
     * @return Positions in call order
     */
    List<String> listedFrom() {
        return this.listed;
    }

    /**
     * Sizes of the delete batches.
     * @return Sizes in call order
     */
    List<Integer> deleteBatches() {
        return this.batches;
    }

    /**
     * Number of abort-uploads calls.
     * @return Count
     */
    int abortCalls() {
        return this.aborted.get();
    }

    @Override
    public Page list(final String dir, final Optional<String> after, final int limit) {
        this.maybeThrottle();
        this.listed.add(after.orElse(""));
        final List<String> page = new ArrayList<>(limit);
        final String prefix = dir + "/";
        for (final String name : this.names) {
            if (name.startsWith(prefix) && after.map(pos -> name.compareTo(pos) > 0).orElse(true)) {
                page.add(name);
                if (page.size() == limit) {
                    break;
                }
            }
        }
        final Optional<String> next;
        if (page.size() == limit) {
            next = Optional.of(page.get(page.size() - 1));
        } else {
            next = Optional.empty();
        }
        return new Page(page, next);
    }

    @Override
    public void delete(final List<String> gone) {
        this.maybeThrottle();
        if (this.failAfter.get() == 0) {
            this.failAfter.set(-1);
            throw new IllegalStateException("storage unavailable");
        }
        if (this.failAfter.get() > 0) {
            this.failAfter.decrementAndGet();
        }
        this.batches.add(gone.size());
        gone.forEach(this.names::remove);
        this.afterDelete.accept(this);
    }

    @Override
    public int abortUploads(final String dir) {
        this.aborted.incrementAndGet();
        return 0;
    }

    /**
     * Throw "slow down" while throttled answers remain.
     */
    private void maybeThrottle() {
        if (this.throttles.get() > 0) {
            this.throttles.decrementAndGet();
            throw new StoreThrottled(new IllegalStateException("SlowDown"));
        }
    }
}
