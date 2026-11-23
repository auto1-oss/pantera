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
package com.auto1.pantera.asto.s3;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Pages through the keys under a path prefix with {@code ListObjectsV2},
 * resuming by {@code StartAfter}: the token is a key string, not the
 * continuation token of one listing, so it survives a restart and the
 * deletion of the keys already returned.
 *
 * <p>The request prefix ends with the delimiter, so {@code maven} never
 * matches {@code maven-proxy/...}. Object keys that no {@link Key} can
 * name (ending with the delimiter, such as the "folder" placeholders some
 * consoles create, or holding an empty segment) are left out; the token
 * still moves past them.</p>
 *
 * @since 2.2.14
 */
final class S3KeyPages {

    /**
     * Most keys S3 returns in one answer.
     */
    private static final int MAX_KEYS = 1000;

    /**
     * S3 client.
     */
    private final S3AsyncClient client;

    /**
     * Bucket name.
     */
    private final String bucket;

    /**
     * Ctor.
     * @param client S3 client
     * @param bucket Bucket name
     */
    S3KeyPages(final S3AsyncClient client, final String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    /**
     * One page of keys.
     * @param prefix Path prefix
     * @param after Resume token
     * @param limit Page size
     * @return Page
     */
    CompletableFuture<ListPage> page(
        final Key prefix, final Optional<String> after, final int limit
    ) {
        final CompletableFuture<ListPage> res;
        if (limit < 1) {
            res = CompletableFuture.failedFuture(
                new IllegalArgumentException(
                    String.format("Page limit must be positive: %d", limit)
                )
            );
        } else {
            final String str = prefix.string();
            final String inside;
            if (str.isEmpty()) {
                inside = str;
            } else {
                inside = str + Key.DELIMITER;
            }
            res = this.fill(
                inside, after.orElse(null), limit,
                new ArrayList<>(Math.min(limit, S3KeyPages.MAX_KEYS))
            ).exceptionallyCompose(
                err -> CompletableFuture.failedFuture(
                    new S3Failure(err).translated(
                        String.format("listing of '%s'", str)
                    )
                )
            );
        }
        return res;
    }

    /**
     * Ask for keys until the page is full or the listing ends.
     * @param inside Request prefix, with trailing delimiter or empty
     * @param after Key to start after, or null
     * @param limit Page size
     * @param keys Keys collected so far
     * @return Page
     */
    private CompletableFuture<ListPage> fill(
        final String inside, final String after, final int limit, final List<Key> keys
    ) {
        final ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
            .bucket(this.bucket)
            .maxKeys(Math.min(limit - keys.size(), S3KeyPages.MAX_KEYS));
        if (!inside.isEmpty()) {
            req.prefix(inside);
        }
        if (after != null) {
            req.startAfter(after);
        }
        return this.client.listObjectsV2(req.build()).thenCompose(
            resp -> {
                String last = after;
                for (final S3Object obj : resp.contents()) {
                    last = obj.key();
                    if (S3KeyPages.nameable(last, inside)) {
                        keys.add(new Key.From(last));
                    }
                }
                final boolean more = Boolean.TRUE.equals(resp.isTruncated())
                    && !resp.contents().isEmpty();
                final CompletableFuture<ListPage> page;
                if (more && keys.size() < limit) {
                    page = this.fill(inside, last, limit, keys);
                } else if (more) {
                    page = CompletableFuture.completedFuture(
                        new ListPage(keys, Optional.of(last))
                    );
                } else {
                    page = CompletableFuture.completedFuture(
                        new ListPage(keys, Optional.empty())
                    );
                }
                return page;
            }
        );
    }

    /**
     * Whether an object key is strictly inside the prefix and can be named
     * by a {@link Key}.
     * @param raw Object key
     * @param inside Request prefix
     * @return True if so
     */
    private static boolean nameable(final String raw, final String inside) {
        return raw.length() > inside.length()
            && !raw.endsWith(Key.DELIMITER)
            && !raw.startsWith(Key.DELIMITER)
            && !raw.contains("//");
    }
}
