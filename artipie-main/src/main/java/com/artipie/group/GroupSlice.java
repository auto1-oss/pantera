/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.jcabi.log.Logger;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Group/virtual repository slice.
 *
 * A read-only facade that tries member repositories in order and returns
 * the first non-404 response. If all members return 404, responds with 404.
 * Uploads are not supported and return 405.
 */
public final class GroupSlice implements Slice {

    /**
     * Maximum number of members queried in parallel.
     */
    private static final int PARALLEL_BATCH_SIZE = 3;

    /**
     * Repository slices resolver/cache.
     */
    private final SliceResolver resolver;

    /**
     * Group repository name.
     */
    private final String group;

    /**
     * Members in resolution priority order.
     */
    private final List<String> members;

    /**
     * Server port used for resolving member slices.
     */
    private final int port;

    /**
     * Ctor.
     *
     * @param resolver Slice resolver/cache
     * @param group Group repository name
     * @param members Member repository names in priority order
     * @param port Server port
     */
    public GroupSlice(final SliceResolver resolver, final String group, final List<String> members, final int port) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.group = Objects.requireNonNull(group, "group");
        this.members = Objects.requireNonNull(members, "members");
        this.port = port;
    }

    @Override
    public CompletableFuture<Response> response(final RequestLine line, final Headers headers, final Content body) {
        final String method = line.method().value();
        final String path = line.uri().getPath();
        
        // Allow read-only methods - use PARALLEL strategy
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return tryAllMembersInParallel(line, headers, body);
        }
        
        // Allow POST for npm audit endpoints - use PARALLEL strategy
        if ("POST".equals(method) && path.contains("/-/npm/v1/security/")) {
            return tryAllMembersInParallel(line, headers, body);
        }
        
        // Allow PUT for npm adduser/login endpoints (only for npm-group)
        if ("PUT".equals(method) && this.isNpmGroup() && this.isNpmAuthEndpoint(path)) {
            // Forward to first member (typically the local npm repo)
            // Auth operations should NOT be parallel (security concern)
            return tryMember(0, line, headers, body);
        }
        
        // Block all other write operations (PUT, DELETE, etc.)
        return ResponseBuilder.methodNotAllowed().completedFuture();
    }
    
    /**
     * Check if this is an npm-group repository.
     * @return True if group name indicates npm-group
     */
    private boolean isNpmGroup() {
        // Check if group name ends with _group (npm_group) or contains "npm"
        return this.group != null && 
               (this.group.contains("npm") || this.group.equals("npm-group"));
    }
    
    /**
     * Check if path is an npm authentication endpoint.
     * @param path Request path
     * @return True if this is npm adduser/login endpoint
     */
    private boolean isNpmAuthEndpoint(final String path) {
        return path.contains("/-/user/org.couchdb.user:");
    }

    /**
     * Try all members in parallel (race strategy).
     * Returns first successful (non-404) response.
     * This is 3-10× faster than sequential when artifact is in last repo.
     *
     * @param line Request line
     * @param headers Request headers
     * @param body Request body
     * @return First successful response, or 404 if all fail
     */
    private CompletableFuture<Response> tryAllMembersInParallel(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        if (this.members.isEmpty()) {
            return ResponseBuilder.notFound().completedFuture();
        }
        final CompletableFuture<Response> result = new CompletableFuture<>();
        final AtomicBoolean terminal = new AtomicBoolean(false);
        this.tryBatch(
            0,
            GroupSlice.PARALLEL_BATCH_SIZE,
            line,
            headers,
            body,
            terminal,
            result
        );
        return result;
    }

    private void tryBatch(
        final int start,
        final int batchSize,
        final RequestLine line,
        final Headers headers,
        final Content body,
        final AtomicBoolean terminal,
        final CompletableFuture<Response> result
    ) {
        if (terminal.get()) {
            return;
        }
        if (start >= this.members.size()) {
            this.completeNotFound(terminal, result, "members exhausted");
            return;
        }
        final int end = Math.min(start + batchSize, this.members.size());
        final int expected = end - start;
        final AtomicInteger failures = new AtomicInteger(0);
        final Headers sanitized = dropFullPathHeader(headers);
        final CopyOnWriteArrayList<CompletableFuture<Response>> futures = new CopyOnWriteArrayList<>();
        for (int idx = start; idx < end; idx++) {
            final String member = this.members.get(idx);
            final Slice memberSlice = this.resolver.slice(new Key.From(member), this.port);
            final RequestLine rewritten = rewrite(line, member);
            Logger.debug(
                this,
                "Group %s processing batch [%d,%d) member %s: %s",
                this.group,
                start,
                end,
                member,
                rewritten.uri()
            );
            final CompletableFuture<Response> memberFuture = memberSlice.response(rewritten, sanitized, body);
            futures.add(memberFuture);
            memberFuture.whenComplete(
                (resp, err) -> {
                    if (terminal.get()) {
                        if (resp != null) {
                            Logger.debug(
                                this,
                                "Group %s ignoring late response from %s for %s - already completed",
                                this.group,
                                member,
                                rewritten.uri()
                            );
                            drainResponseBody(resp.body());
                        }
                        return;
                    }
                    if (err != null) {
                        if (err instanceof CancellationException) {
                            Logger.debug(
                                this,
                                "Group %s member %s cancelled: %s",
                                this.group,
                                member,
                                err.getMessage()
                            );
                        } else {
                            Logger.warn(
                                this,
                                "Group %s member %s failed: %s",
                                this.group,
                                member,
                                err.getMessage()
                            );
                        }
                        if (failures.incrementAndGet() == expected) {
                            this.tryBatch(end, batchSize, line, sanitized, body, terminal, result);
                        }
                        return;
                    }
                    Logger.debug(
                        this,
                        "Member %s responded %s for %s",
                        member,
                        resp.status(),
                        rewritten.uri()
                    );
                    if (resp.status() == RsStatus.NOT_FOUND) {
                        drainResponseBody(resp.body());
                        if (failures.incrementAndGet() == expected) {
                            this.tryBatch(end, batchSize, line, sanitized, body, terminal, result);
                        }
                        return;
                    }
                    if (terminal.compareAndSet(false, true)) {
                        Logger.info(
                            this,
                            "Group %s: member %s returned SUCCESS",
                            this.group,
                            member
                        );
                        result.complete(resp);
                        cancelPending(futures, memberFuture, String.format("member %s success", member));
                    } else {
                        Logger.warn(
                            this,
                            "Duplicate success from %s ignored for %s",
                            member,
                            rewritten.uri()
                        );
                        drainResponseBody(resp.body());
                    }
                }
            );
        }
        if (terminal.get()) {
            cancelPending(futures, null, "terminal cleanup");
        }
    }

    private static void cancelPending(
        final List<CompletableFuture<Response>> futures,
        final CompletableFuture<Response> winner,
        final String reason
    ) {
        for (CompletableFuture<Response> future : futures) {
            if (future != winner && !future.isDone()) {
                future.cancel(true);
            }
        }
        Logger.debug(
            GroupSlice.class,
            "Cancelled non-winning futures after %s",
            reason
        );
    }

    private void completeNotFound(
        final AtomicBoolean terminal,
        final CompletableFuture<Response> result,
        final String reason
    ) {
        if (terminal.compareAndSet(false, true)) {
            result.complete(ResponseBuilder.notFound().build());
        } else {
            Logger.debug(
                this,
                "Group %s already completed when attempting 404 due to %s",
                this.group,
                reason
            );
        }
    }

    /**
     * Try members sequentially (for write operations like auth).
     * Used only for operations that should NOT be parallelized.
     *
     * @param index Current member index
     * @param line Request line
     * @param headers Request headers
     * @param body Request body
     * @return Response from first successful member
     */
    private CompletableFuture<Response> tryMember(
        final int index,
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        if (index >= this.members.size()) {
            return ResponseBuilder.notFound().completedFuture();
        }
        final String member = this.members.get(index);
        final Slice memberSlice = this.resolver.slice(new Key.From(member), this.port);
        final RequestLine rewritten = rewrite(line, member);
        final Headers sanitized = dropFullPathHeader(headers);
        Logger.debug(this, "Group %s trying member %s: %s", this.group, member, rewritten.uri());
        return memberSlice.response(rewritten, sanitized, body).thenCompose(resp -> {
            Logger.debug(this, "Member %s responded %s for %s", member, resp.status(), rewritten.uri());
            if (resp.status() == RsStatus.NOT_FOUND) {
                // IMPORTANT: pass sanitized headers to the next member as well to avoid
                // TrimPathSlice recursion detection (X-FullPath) breaking path rewriting.
                return tryMember(index + 1, line, sanitized, body);
            }
            return CompletableFuture.completedFuture(resp);
        });
    }

    /**
     * Rewrite request line by prefixing member repo name to path.
     * Assumes the path is already trimmed (group name removed) by outer TrimPathSlice.
     *
     * @param original Original request line
     * @param member Member repository name to prefix
     * @return New request line
     */
    private static RequestLine rewrite(final RequestLine original, final String member) {
        final URI uri = original.uri();
        final String raw = uri.getRawPath();
        final String base = raw.startsWith("/") ? raw : "/" + raw;
        final String pref = "/" + member + "/";
        final String path = base.equals("/") ? "/" : base;
        final String combined = path.startsWith(pref)
            ? path
            : ("/" + member + (path.equals("/") ? "" : path));
        final StringBuilder full = new StringBuilder(combined);
        if (uri.getRawQuery() != null) {
            full.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            full.append('#').append(uri.getRawFragment());
        }
        return new RequestLine(original.method().value(), full.toString(), original.version());
    }

    private static Headers dropFullPathHeader(final Headers headers) {
        final String hdr = "X-FullPath";
        return new Headers(
            headers.asList().stream()
                .filter(h -> !h.getKey().equalsIgnoreCase(hdr))
                .toList()
        );
    }
    
    /**
     * Drains a content body by consuming all bytes without processing them.
     * This ensures the underlying HTTP connection is properly released.
     */
    private static void drainResponseBody(final Content body) {
        if (body != null) {
            body.subscribe(new Subscriber<ByteBuffer>() {
                private Subscription subscription;
                
                @Override
                public void onSubscribe(final Subscription s) {
                    this.subscription = s;
                    // Request all data to force consumption
                    s.request(Long.MAX_VALUE);
                }
                
                @Override
                public void onNext(final ByteBuffer buffer) {
                    // Discard the buffer - just consuming to drain
                }
                
                @Override
                public void onError(final Throwable t) {
                    Logger.debug(GroupSlice.class, "Error draining response body: %s", t.getMessage());
                }
                
                @Override
                public void onComplete() {
                    Logger.debug(GroupSlice.class, "Successfully drained response body");
                }
            });
        }
    }
}
