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
package com.auto1.pantera.docker.cache;

import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.http.log.EcsLogger;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * When {@link CacheManifests} copies a manifest the origin returned into
 * the cache (storing it and queueing its artifact event): at once, never,
 * once the request it was read for has been answered, or once a verdict
 * on the manifest allows it.
 *
 * <p>A proxy with cooldown decides on a manifest after the cache layer has
 * read it from the origin: the cooldown slice above the registry API
 * evaluates the tag and the digest the origin returned. A copy made at
 * once stores and indexes a version cooldown then refuses. A client's
 * manifest request is therefore read through a view whose copies are held
 * here until the answer is known: {@link #commit()} when the version is
 * served, {@link #discard()} when it is refused.</p>
 *
 * <p>A copy handed over after the decision follows it: run at once after
 * {@link #commit()}, dropped after {@link #discard()}. Only the first
 * decision counts.</p>
 *
 * <p>The answer to a {@code HEAD} is never refused, so it decides nothing.
 * A {@code HEAD} is read through a view whose copies start at once, as in
 * the repository's own view (reading the release date and size of the
 * manifest from the origin), and ask a {@link Verdict} just before they
 * store: the release date is known by then, so the verdict needs no
 * request of its own to the origin.</p>
 *
 * @since 2.2.15
 */
public final class ManifestCopies {

    /**
     * Verdict of copies whose store was decided before they started.
     */
    private static final Verdict STORES =
        (image, ref, manifest) -> CompletableFuture.completedFuture(true);

    /**
     * Every copy is made at once: the repository's own view.
     */
    static final ManifestCopies AT_ONCE =
        new ManifestCopies(State.COMMITTED, ManifestCopies.STORES);

    /**
     * No copy is made: a request that must leave the repository as it found
     * it.
     */
    static final ManifestCopies NEVER =
        new ManifestCopies(State.DISCARDED, ManifestCopies.STORES);

    /**
     * Copies handed over before the decision.
     */
    private final Queue<Runnable> held;

    /**
     * Decision on the copies.
     */
    private final AtomicReference<State> state;

    /**
     * Verdict a copy asks just before it stores.
     */
    private final Verdict verdict;

    /**
     * Copies held until {@link #commit()} or {@link #discard()}.
     */
    public ManifestCopies() {
        this(State.HELD, ManifestCopies.STORES);
    }

    /**
     * Copies that start at once and store only what the verdict allows.
     *
     * @param verdict Verdict asked just before a copy stores
     */
    public ManifestCopies(final Verdict verdict) {
        this(State.COMMITTED, verdict);
    }

    /**
     * Ctor.
     *
     * @param state Decision at first
     * @param verdict Verdict asked just before a copy stores
     */
    private ManifestCopies(final State state, final Verdict verdict) {
        this.held = new ConcurrentLinkedQueue<>();
        this.state = new AtomicReference<>(state);
        this.verdict = verdict;
    }

    /**
     * Hand over a copy: made at once, held or dropped as decided.
     *
     * @param copy Copy of a manifest into the cache
     */
    void submit(final Runnable copy) {
        final State now = this.state.get();
        if (now == State.COMMITTED) {
            ManifestCopies.run(copy);
        } else if (now == State.HELD) {
            this.held.add(copy);
            // The decision may have come while the copy was being added.
            this.settle();
        }
    }

    /**
     * Make a copy and wait for it, when copies are made at once. A refresh
     * of a cached tag answers with what the cache holds once its copy is
     * decided, so it cannot leave the copy for after the answer.
     *
     * @param copy The copy
     * @return True once the copy is done; false at once when copies are
     *  discarded (nothing is stored) or held for the decision on the
     *  answer (the copy then waits for that decision)
     */
    CompletionStage<Boolean> now(final Supplier<CompletionStage<Void>> copy) {
        final State now = this.state.get();
        CompletionStage<Boolean> res;
        if (now == State.COMMITTED) {
            try {
                res = copy.get().thenApply(ignored -> true);
            } catch (final RuntimeException ex) {
                res = CompletableFuture.failedFuture(ex);
            }
        } else {
            if (now == State.HELD) {
                this.submit(copy::get);
            }
            res = CompletableFuture.completedFuture(false);
        }
        return res;
    }

    /**
     * Whether a copy that has read what it needs stores the manifest. A
     * verdict that fails allows, as cooldown fails open.
     *
     * @param image Image name
     * @param ref Reference the manifest was read by
     * @param manifest Manifest the origin returned
     * @return Whether to store it
     */
    CompletionStage<Boolean> stores(
        final String image, final ManifestReference ref, final Manifest manifest
    ) {
        CompletionStage<Boolean> res;
        try {
            res = this.verdict.stores(image, ref, manifest);
        } catch (final RuntimeException ex) {
            res = CompletableFuture.failedFuture(ex);
        }
        return res.exceptionally(
            err -> {
                EcsLogger.warn("com.auto1.pantera.docker")
                    .message("Failed to decide on caching a manifest; caching it")
                    .eventCategory("web")
                    .eventAction("manifest_cache")
                    .eventOutcome("failure")
                    .field("container.image.name", image)
                    .field("container.image.hash.all", ref.digest())
                    .error(err)
                    .field("log.source", "application")
                    .log();
                return true;
            }
        );
    }

    /**
     * The answer serves the version: make the copies held and those handed
     * over later.
     */
    public void commit() {
        this.state.compareAndSet(State.HELD, State.COMMITTED);
        this.settle();
    }

    /**
     * The answer does not serve the version: drop the copies held and those
     * handed over later.
     */
    public void discard() {
        this.state.compareAndSet(State.HELD, State.DISCARDED);
        this.settle();
    }

    /**
     * Make or drop what is held, as decided; nothing before a decision.
     * Each held copy is taken off the queue once, so it runs at most once
     * whichever thread settles.
     */
    private void settle() {
        final State now = this.state.get();
        if (now != State.HELD) {
            for (Runnable copy = this.held.poll(); copy != null; copy = this.held.poll()) {
                if (now == State.COMMITTED) {
                    ManifestCopies.run(copy);
                }
            }
        }
    }

    /**
     * Start one copy. It runs on the thread that decides, which completes
     * the client's answer: a copy that fails to start is logged and never
     * fails that answer.
     *
     * @param copy Copy of a manifest into the cache
     */
    private static void run(final Runnable copy) {
        try {
            copy.run();
        } catch (final RuntimeException ex) {
            EcsLogger.error("com.auto1.pantera.docker")
                .message("Failed to start caching a manifest")
                .eventCategory("web")
                .eventAction("manifest_cache")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Whether a manifest read by a request whose answer decides nothing is
     * stored.
     *
     * @since 2.2.15
     */
    @FunctionalInterface
    public interface Verdict {

        /**
         * Whether to store a manifest.
         *
         * @param image Image name, as the repository's cache names it
         * @param ref Tag or digest the manifest was read by
         * @param manifest Manifest the origin returned
         * @return True to store it and queue its artifact event
         */
        CompletionStage<Boolean> stores(String image, ManifestReference ref, Manifest manifest);
    }

    /**
     * Decision on the copies.
     *
     * @since 2.2.15
     */
    private enum State {
        /**
         * Not decided yet: copies are held.
         */
        HELD,

        /**
         * Copies are made.
         */
        COMMITTED,

        /**
         * Copies are dropped.
         */
        DISCARDED
    }
}
