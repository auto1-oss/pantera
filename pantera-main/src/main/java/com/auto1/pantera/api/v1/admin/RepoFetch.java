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
package com.auto1.pantera.api.v1.admin;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * In-process GET against a repository, answered exactly as the repository
 * would answer a client: the request enters the repository's own slice chain
 * (authentication and authorization included) with the caller's credentials.
 *
 * @since 2.2.9
 */
public interface RepoFetch {

    /**
     * Fetch that is not wired (tests, DB-less boots).
     */
    RepoFetch UNAVAILABLE = (repo, path, auth, accept, maxBody) ->
        CompletableFuture.failedFuture(
            new IllegalStateException("In-process repository fetch is not available")
        );

    /**
     * GET a repository-relative path.
     *
     * @param repo Repository name
     * @param path Repository-relative raw (still percent-encoded) path
     *  starting with {@code /}, may carry a query
     * @param authorization Caller's {@code Authorization} header value, may be null
     * @param accept {@code Accept} header value, may be null
     * @param maxBody Maximum body bytes kept; the rest is drained and discarded
     * @return Future of the response
     */
    CompletableFuture<Fetched> get(
        String repo, String path, String authorization, String accept, int maxBody
    );

    /**
     * Response of an in-process fetch.
     *
     * @param status HTTP status
     * @param headers Response headers (name, value) in order
     * @param body Kept body bytes
     * @param truncated Whether the body exceeded the kept bound
     * @param bytes Total body size seen
     * @since 2.2.9
     */
    record Fetched(
        int status, List<Map.Entry<String, String>> headers, byte[] body,
        boolean truncated, long bytes
    ) {

        /**
         * Ctor.
         *
         * @param status Status
         * @param headers Headers
         * @param body Body
         * @param truncated Truncated flag
         * @param bytes Total size
         */
        public Fetched {
            headers = List.copyOf(headers);
            body = body.clone();
        }

        /**
         * First header value by case-insensitive name.
         *
         * @param name Header name
         * @return Value or null
         */
        public String header(final String name) {
            return this.headers.stream()
                .filter(hdr -> hdr.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        }

        @Override
        public byte[] body() {
            return this.body.clone();
        }
    }

    /**
     * Reactive body reader keeping at most {@code max} bytes and draining the
     * remainder, so an arbitrarily large artifact never lands in memory and
     * the publisher is always fully consumed.
     *
     * @since 2.2.9
     */
    final class BoundedBody implements Subscriber<ByteBuffer> {

        /**
         * Kept bytes.
         */
        private final java.io.ByteArrayOutputStream kept;

        /**
         * Bound.
         */
        private final int max;

        /**
         * Completion.
         */
        private final CompletableFuture<BoundedBody> done;

        /**
         * Total bytes seen.
         */
        private long total;

        /**
         * Ctor.
         *
         * @param max Bound
         */
        public BoundedBody(final int max) {
            this.kept = new java.io.ByteArrayOutputStream(Math.min(Math.max(max, 0), 65_536));
            this.max = max;
            this.done = new CompletableFuture<>();
        }

        /**
         * Read a publisher.
         *
         * @param body Body publisher
         * @return Future of this reader once the body completed
         */
        public CompletableFuture<BoundedBody> read(final Publisher<ByteBuffer> body) {
            body.subscribe(this);
            return this.done;
        }

        /**
         * Kept bytes.
         *
         * @return Bytes
         */
        public byte[] bytes() {
            return this.kept.toByteArray();
        }

        /**
         * Total size seen.
         *
         * @return Bytes
         */
        public long total() {
            return this.total;
        }

        /**
         * Whether bytes were dropped.
         *
         * @return True when the body exceeded the bound
         */
        public boolean truncated() {
            return this.total > this.kept.size();
        }

        @Override
        public void onSubscribe(final Subscription sub) {
            sub.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(final ByteBuffer item) {
            final int len = item.remaining();
            this.total = this.total + len;
            final int room = this.max - this.kept.size();
            if (room > 0) {
                final byte[] chunk = new byte[Math.min(room, len)];
                item.get(chunk);
                this.kept.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(final Throwable err) {
            this.done.completeExceptionally(err);
        }

        @Override
        public void onComplete() {
            this.done.complete(this);
        }
    }
}
