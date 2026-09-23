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
package com.auto1.pantera.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Go group slice: merges {@code <module>/@v/list} across all members.
 *
 * <p>A module's version list is the union of what every member knows: the
 * hosted member's private versions and the proxy's upstream versions. The
 * generic first-wins walk served one member's list only, so {@code go list
 * -m -versions} and {@code @latest} resolution saw either the upstream or
 * the local versions, never both. Every other request (and a list no
 * member can answer) goes to the {@link GroupResolver} walk.
 *
 * @since 2.2.9
 */
public final class GoGroupSlice implements Slice {

    /**
     * Suffix of the Go module proxy version-list endpoint.
     */
    private static final String LIST_SUFFIX = "/@v/list";

    /**
     * Group resolver handling every other request.
     */
    private final Slice delegate;

    /**
     * Flattened members in declared order.
     */
    private final List<MemberSlice> members;

    /**
     * Wiring constructor.
     *
     * @param delegate Group resolver handling every other request
     * @param resolver Slice resolver for member repositories
     * @param memberNames Flattened member names in declared order
     * @param port Server port passed to the resolver
     */
    public GoGroupSlice(
        final Slice delegate,
        final SliceResolver resolver,
        final List<String> memberNames,
        final int port
    ) {
        this(delegate, buildMembers(resolver, memberNames, port));
    }

    /**
     * Primary constructor.
     *
     * @param delegate Group resolver handling every other request
     * @param members Flattened members in declared order
     */
    private GoGroupSlice(final Slice delegate, final List<MemberSlice> members) {
        this.delegate = delegate;
        this.members = members;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        if (!"GET".equals(line.method().value())
            || !line.uri().getPath().endsWith(LIST_SUFFIX)) {
            return this.delegate.response(line, headers, body);
        }
        return body.asBytesFuture().thenCompose(ignored -> this.mergeLists(line, headers));
    }

    /**
     * Ask every member for the list and answer the de-duplicated union in
     * member order; fall back to the walk when no member answered 200.
     */
    private CompletableFuture<Response> mergeLists(final RequestLine line, final Headers headers) {
        final Headers memberHeaders = new Headers(
            headers.asList().stream()
                .filter(h -> !"X-FullPath".equalsIgnoreCase(h.getKey()))
                .toList()
        ).copy().add(new Header(EcsLoggingSlice.INTERNAL_ROUTING_HEADER, "true"));
        final List<CompletableFuture<Optional<byte[]>>> lists = new ArrayList<>(this.members.size());
        for (final MemberSlice member : this.members) {
            lists.add(fetchList(member, line, memberHeaders));
        }
        return CompletableFuture.allOf(lists.toArray(CompletableFuture[]::new))
            .thenCompose(done -> {
                final Set<String> versions = new LinkedHashSet<>();
                boolean answered = false;
                for (final CompletableFuture<Optional<byte[]>> list : lists) {
                    final Optional<byte[]> bytes = list.join();
                    if (bytes.isPresent()) {
                        answered = true;
                        for (final String ver
                            : new String(bytes.get(), StandardCharsets.UTF_8).split("\n")) {
                            if (!ver.isBlank()) {
                                versions.add(ver.trim());
                            }
                        }
                    }
                }
                if (!answered) {
                    return this.delegate.response(line, headers, Content.EMPTY);
                }
                final StringBuilder out = new StringBuilder();
                versions.forEach(ver -> out.append(ver).append('\n'));
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().textBody(out.toString()).build()
                );
            });
    }

    /**
     * One member's version list; empty when it did not answer 200.
     */
    private static CompletableFuture<Optional<byte[]>> fetchList(
        final MemberSlice member, final RequestLine line, final Headers headers
    ) {
        return member.slice().response(member.rewritePath(line), headers, Content.EMPTY)
            .thenCompose(resp -> resp.body().asBytesFuture().thenApply(bytes -> {
                if (resp.status() == RsStatus.OK) {
                    return Optional.of(bytes);
                }
                return Optional.<byte[]>empty();
            }))
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.group")
                    .message("Go group member failed to answer the version list: "
                        + member.name())
                    .eventCategory("web")
                    .eventAction("group_go_list_member_failed")
                    .eventOutcome("failure")
                    .field("url.path", line.uri().getPath())
                    .error(err)
                    .field("log.source", "application")
                    .log();
                return Optional.empty();
            });
    }

    private static List<MemberSlice> buildMembers(
        final SliceResolver resolver, final List<String> names, final int port
    ) {
        final List<MemberSlice> out = new ArrayList<>(names.size());
        for (final String name : new LinkedHashSet<>(names)) {
            out.add(new MemberSlice(name, resolver.slice(new Key.From(name), port, 0)));
        }
        return out;
    }
}
