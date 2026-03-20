/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.dedup;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Content-addressable storage layer for deduplication.
 * Stores blobs by SHA-256 hash with reference counting.
 *
 * @since 1.20.13
 */
public final class ContentAddressableStorage {

    /**
     * Blob reference counts: sha256 -> ref count.
     */
    private final ConcurrentMap<String, AtomicLong> refCounts;

    /**
     * Artifact-to-blob mapping: "repoName::path" -> sha256.
     */
    private final ConcurrentMap<String, String> artifactBlobs;

    /**
     * Underlying storage for actual blob content.
     */
    private final Storage storage;

    /**
     * Ctor.
     * @param storage Underlying storage for blobs
     */
    public ContentAddressableStorage(final Storage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.refCounts = new ConcurrentHashMap<>();
        this.artifactBlobs = new ConcurrentHashMap<>();
    }

    /**
     * Save content with deduplication.
     * If the same SHA-256 already exists, increment ref count instead of storing again.
     *
     * @param repoName Repository name
     * @param path Artifact path
     * @param sha256 SHA-256 hash of the content
     * @param content Content bytes
     * @return Future completing when saved
     */
    public CompletableFuture<Void> save(
        final String repoName, final String path,
        final String sha256, final byte[] content
    ) {
        final String artKey = artKey(repoName, path);
        // Remove old mapping if exists
        final String oldSha = this.artifactBlobs.put(artKey, sha256);
        if (oldSha != null && !oldSha.equals(sha256)) {
            this.decrementRef(oldSha);
        }
        // Increment ref count
        this.refCounts.computeIfAbsent(sha256, k -> new AtomicLong(0)).incrementAndGet();
        // Store blob if new
        final Key blobKey = blobKey(sha256);
        return this.storage.exists(blobKey).thenCompose(exists -> {
            if (exists) {
                return CompletableFuture.completedFuture(null);
            }
            return this.storage.save(blobKey, new com.auto1.pantera.asto.Content.From(content))
                .toCompletableFuture();
        });
    }

    /**
     * Delete an artifact reference.
     * Decrements ref count and removes blob if zero.
     *
     * @param repoName Repository name
     * @param path Artifact path
     * @return Future completing when deleted
     */
    public CompletableFuture<Void> delete(final String repoName, final String path) {
        final String sha = this.artifactBlobs.remove(artKey(repoName, path));
        if (sha == null) {
            return CompletableFuture.completedFuture(null);
        }
        return this.decrementRef(sha);
    }

    /**
     * Get the ref count for a blob.
     * @param sha256 SHA-256 hash
     * @return Reference count, 0 if not found
     */
    public long refCount(final String sha256) {
        final AtomicLong count = this.refCounts.get(sha256);
        return count != null ? count.get() : 0;
    }

    /**
     * Decrement ref count, delete blob if zero.
     */
    private CompletableFuture<Void> decrementRef(final String sha256) {
        final AtomicLong count = this.refCounts.get(sha256);
        if (count != null && count.decrementAndGet() <= 0) {
            this.refCounts.remove(sha256);
            return this.storage.delete(blobKey(sha256)).toCompletableFuture();
        }
        return CompletableFuture.completedFuture(null);
    }

    private static String artKey(final String repoName, final String path) {
        return repoName + "::" + path;
    }

    private static Key blobKey(final String sha256) {
        return new Key.From(
            ".cas",
            sha256.substring(0, 2),
            sha256.substring(2, 4),
            sha256
        );
    }
}
