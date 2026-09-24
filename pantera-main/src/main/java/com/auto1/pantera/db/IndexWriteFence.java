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
package com.auto1.pantera.db;

import com.auto1.pantera.scheduling.ArtifactEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;

/**
 * Keeps index rows of deleted repositories and paths from coming back.
 *
 * <p>Uploads reach the {@code artifacts} table asynchronously: an event waits
 * in the events queue and then in the {@link DbConsumer} batch window before
 * it is written. A repository or path delete that purged the table in the
 * meantime was followed by the late insert, and the deleted artifact stayed
 * searchable.</p>
 *
 * <p>A delete first {@linkplain #fenceRepository fences} the repository (or
 * {@linkplain #fencePath path}), then purges the rows. The fence records the
 * latest {@link ArtifactEvent#sequence()}; the writer drops every insert
 * event created up to it. Registering a fence waits for batches in flight
 * (they hold {@link #writing()}), so a batch either committed before the fence
 * (and its rows are purged by the delete) or starts after it (and drops the
 * fenced events). Events created after the delete, e.g. for a repository
 * re-created under the same name, are written normally.</p>
 *
 * <p>Blocking: {@link #writing()} and the fence calls may wait on a batch
 * transaction; callers run on database or worker threads, never on the event
 * loop. Fences expire after {@link #KEEP}, far beyond any queue delay.</p>
 *
 * @since 2.2.9
 */
public final class IndexWriteFence {

    /**
     * How long a fence is kept.
     */
    static final Duration KEEP = Duration.ofMinutes(15);

    /**
     * Process-wide fence shared by the index writers and the delete paths.
     */
    private static final IndexWriteFence SHARED = new IndexWriteFence(System::currentTimeMillis);

    /**
     * Batches hold the read side; fence registration takes the write side.
     */
    private final ReadWriteLock lock;

    /**
     * Fences per repository name.
     */
    private final Map<String, List<Fence>> fences;

    /**
     * Wall clock in milliseconds, for expiry.
     */
    private final LongSupplier clock;

    /**
     * Ctor.
     * @param clock Wall clock in milliseconds
     */
    IndexWriteFence(final LongSupplier clock) {
        this.lock = new ReentrantReadWriteLock();
        this.fences = new ConcurrentHashMap<>();
        this.clock = clock;
    }

    /**
     * The process-wide fence.
     * @return Shared fence
     */
    public static IndexWriteFence shared() {
        return IndexWriteFence.SHARED;
    }

    /**
     * Hold the writer side while a batch runs; close to release.
     * @return Handle releasing the hold
     */
    public Held writing() {
        final Lock read = this.lock.readLock();
        read.lock();
        return read::unlock;
    }

    /**
     * Fence every event of a repository created so far.
     * @param repo Repository name
     */
    public void fenceRepository(final String repo) {
        this.fence(repo, null);
    }

    /**
     * Fence the events created so far for a path of a repository: the
     * artifacts named {@code path} or below it, or whose path prefix is.
     * @param repo Repository name
     * @param path Deleted path (a file or a folder)
     */
    public void fencePath(final String repo, final String path) {
        this.fence(repo, IndexWriteFence.trim(path));
    }

    /**
     * Whether an insert event was superseded by a later delete.
     * @param event Event
     * @return True if the event must not be written
     */
    public boolean fenced(final ArtifactEvent event) {
        boolean res = false;
        if (event.eventType() == ArtifactEvent.Type.INSERT && event.repoName() != null) {
            final List<Fence> list = this.fences.get(event.repoName().trim());
            if (list != null) {
                for (final Fence fence : list) {
                    if (fence.covers(event)) {
                        res = true;
                        break;
                    }
                }
            }
        }
        return res;
    }

    /**
     * Register a fence, waiting for the batches in flight.
     * @param repo Repository name
     * @param path Path, or null for the whole repository
     */
    private void fence(final String repo, final String path) {
        if (repo == null || repo.isBlank()) {
            return;
        }
        final Lock write = this.lock.writeLock();
        write.lock();
        try {
            final long now = this.clock.getAsLong();
            this.expire(now);
            this.fences.computeIfAbsent(repo.trim(), key -> new CopyOnWriteArrayList<>())
                .add(new Fence(ArtifactEvent.latestSequence(), now, path));
        } finally {
            write.unlock();
        }
    }

    /**
     * Drop the fences older than {@link #KEEP}.
     * @param now Current time in milliseconds
     */
    private void expire(final long now) {
        final long oldest = now - IndexWriteFence.KEEP.toMillis();
        this.fences.values().forEach(list -> list.removeIf(fence -> fence.created() < oldest));
        this.fences.values().removeIf(List::isEmpty);
    }

    /**
     * Strip leading and trailing slashes.
     * @param path Path
     * @return Trimmed path
     */
    private static String trim(final String path) {
        String res = path == null ? "" : path;
        while (res.startsWith("/")) {
            res = res.substring(1);
        }
        while (res.endsWith("/")) {
            res = res.substring(0, res.length() - 1);
        }
        return res;
    }

    /**
     * Release handle of {@link #writing()}.
     */
    @FunctionalInterface
    public interface Held extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * One fence.
     * @param sequence Latest event sequence when the fence was registered
     * @param created Registration time in milliseconds
     * @param path Fenced path, or null for the whole repository
     */
    private record Fence(long sequence, long created, String path) {

        /**
         * Whether this fence supersedes an event of its repository. Mirrors
         * the rows {@code DbArtifactIndex.removeByPath} deletes.
         * @param event Event
         * @return True if superseded
         */
        boolean covers(final ArtifactEvent event) {
            if (event.sequence() > this.sequence) {
                return false;
            }
            if (this.path == null) {
                return true;
            }
            if (this.path.isEmpty()) {
                return false;
            }
            return Fence.under(event.artifactName(), this.path)
                || Fence.under(IndexWriteFence.trim(event.pathPrefix()), this.path);
        }

        /**
         * Whether a value is the path or below it.
         * @param value Artifact name or path prefix, nullable
         * @param path Fenced path
         * @return True if under
         */
        private static boolean under(final String value, final String path) {
            return value != null && (value.equals(path) || value.startsWith(path + "/"));
        }
    }
}
