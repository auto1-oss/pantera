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

import com.auto1.pantera.docker.manifest.Manifest;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * When a docker-proxy repository last asked its upstream about each tag it
 * holds, and how old that answer may get before a request of the tag asks
 * again (the tag refresh age).
 *
 * <p>A digest names one image for ever, a tag ({@code latest}, {@code 20})
 * is moved by its owner. A cached tag is served from the cache while its
 * last check is younger than the age; the first request after that asks
 * the upstream, once for all concurrent requests of the tag, and the tag
 * counts as checked when the answer is in, whatever it was: an upstream
 * that is down is asked again one age later, not on every request.</p>
 *
 * <p>Kept in memory, per repository and per node: a check costs no storage
 * request, and after a restart each tag is checked once, on its first
 * request. The age is read on every decision, so a changed setting applies
 * at once.</p>
 *
 * @since 2.2.15
 */
public final class TagChecks {

    /**
     * Tags remembered per repository; the least recently used are
     * forgotten first and are then checked on their next request.
     */
    private static final long MAX_TAGS = 100_000L;

    /**
     * Tag refresh age; zero checks on every request.
     */
    private final Supplier<Duration> age;

    /**
     * Clock.
     */
    private final Clock clock;

    /**
     * Time of the last check by image and tag.
     */
    private final Cache<String, Instant> checked;

    /**
     * Checks under way by image and tag.
     */
    private final ConcurrentMap<String, CompletableFuture<Optional<Manifest>>> running;

    /**
     * Ctor.
     *
     * @param age Tag refresh age, read on every decision
     */
    public TagChecks(final Supplier<Duration> age) {
        this(age, Clock.systemUTC());
    }

    /**
     * Ctor.
     *
     * @param age Tag refresh age, read on every decision
     * @param clock Clock
     */
    public TagChecks(final Supplier<Duration> age, final Clock clock) {
        this.age = age;
        this.clock = clock;
        this.checked = Caffeine.newBuilder().maximumSize(TagChecks.MAX_TAGS).build();
        this.running = new ConcurrentHashMap<>();
    }

    /**
     * Whether the cached copy of a tag may be served without asking the
     * upstream.
     *
     * @param image Image name
     * @param tag Tag
     * @return True when the tag was checked less than the age ago
     */
    boolean young(final String image, final String tag) {
        final Instant last = this.checked.getIfPresent(TagChecks.key(image, tag));
        final Duration max = this.age.get();
        return last != null && max != null && max.isPositive()
            && last.plus(max).isAfter(this.clock.instant());
    }

    /**
     * Note that the upstream was asked about a tag just now.
     *
     * @param image Image name
     * @param tag Tag
     */
    void asked(final String image, final String tag) {
        this.checked.put(TagChecks.key(image, tag), this.clock.instant());
    }

    /**
     * Ask the upstream about a tag, once for all concurrent requests of it.
     * The tag counts as checked when the answer is in, a failure included.
     *
     * @param image Image name
     * @param tag Tag
     * @param ask The check
     * @return What the check found, shared by the requests that waited for it
     */
    CompletableFuture<Optional<Manifest>> check(
        final String image, final String tag,
        final Supplier<CompletionStage<Optional<Manifest>>> ask
    ) {
        final String key = TagChecks.key(image, tag);
        final CompletableFuture<Optional<Manifest>> mine = new CompletableFuture<>();
        final CompletableFuture<Optional<Manifest>> other = this.running.putIfAbsent(key, mine);
        final CompletableFuture<Optional<Manifest>> res;
        if (other == null) {
            CompletionStage<Optional<Manifest>> stage;
            try {
                stage = ask.get();
            } catch (final RuntimeException ex) {
                stage = CompletableFuture.failedFuture(ex);
            }
            stage.whenComplete(
                (found, err) -> {
                    // Noted before the check leaves the map: a request that
                    // comes in between finds the tag young.
                    this.asked(image, tag);
                    this.running.remove(key, mine);
                    if (err == null) {
                        mine.complete(found);
                    } else {
                        mine.completeExceptionally(err);
                    }
                }
            );
            res = mine;
        } else {
            res = other;
        }
        return res;
    }

    /**
     * Key of a tag.
     *
     * @param image Image name
     * @param tag Tag
     * @return Key
     */
    private static String key(final String image, final String tag) {
        return image + ':' + tag;
    }
}
