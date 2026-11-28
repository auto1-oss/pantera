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
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.log.RequestReasons;
import com.auto1.pantera.http.log.RequestTrace;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Members of a group, each limited to the paths its routing rules name.
 *
 * <p>A member that has at least one {@link RoutingRule} is asked only for a
 * path one of its rules matches; for any other path it answers 404 without
 * being asked, so the request reaches neither its storage nor its upstream.
 * A member without rules is asked for everything. The rules are applied
 * where a group obtains a member's slice, so every kind of group (artifact
 * walk, Maven metadata, Composer, Go, Docker) obeys them the same way.</p>
 *
 * @since 2.2.14
 */
public final class RoutedMembers implements SliceResolver {

    /**
     * Reason of the "not found" a member answers for a path its routing
     * rules do not match: the member was not asked.
     */
    public static final String SKIPPED = "skipped_by_routing_rule";

    /**
     * Resolver of the members.
     */
    private final SliceResolver origin;

    /**
     * Rules by member name.
     */
    private final Map<String, List<RoutingRule>> rules;

    /**
     * Ctor.
     * @param origin Resolver of the members
     * @param rules Routing rules of the group
     */
    public RoutedMembers(final SliceResolver origin, final List<RoutingRule> rules) {
        this.origin = origin;
        this.rules = rules.stream().collect(Collectors.groupingBy(RoutingRule::member));
    }

    @Override
    public Slice slice(final Key name, final int port, final int depth) {
        final Slice member = this.origin.slice(name, port, depth);
        final List<RoutingRule> own = this.rules.get(name.string());
        final Slice result;
        if (own == null) {
            result = member;
        } else {
            result = new Routed(member, name.string(), own);
        }
        return result;
    }

    /**
     * A member that is asked only for the paths its rules match.
     */
    private static final class Routed implements Slice {

        /**
         * The member.
         */
        /**
         * Member name.
         */
        private final String name;

        private final Slice member;

        /**
         * Path prefix a group puts in front of a member's path.
         */
        private final String prefix;

        /**
         * Rules of the member.
         */
        private final List<RoutingRule> rules;

        Routed(final Slice member, final String name, final List<RoutingRule> rules) {
            this.name = name;
            this.member = member;
            this.prefix = '/' + name + '/';
            this.rules = rules;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final String full = line.uri().getPath();
            final String path;
            if (full.startsWith(this.prefix)) {
                path = full.substring(this.prefix.length() - 1);
            } else {
                path = full;
            }
            final CompletableFuture<Response> result;
            if (this.rules.stream().anyMatch(rule -> rule.matches(path))) {
                result = this.member.response(line, headers, body);
            } else {
                // Not asked: the answer is a "not found" that says why, for
                // the access record and the Troubleshoot trace.
                RequestReasons.instance().note(headers, RoutedMembers.SKIPPED);
                if (RequestTrace.instance().traced(headers)) {
                    RequestTrace.instance().step(
                        headers,
                        new RequestTrace.Step(
                            this.name, "", RsStatus.NOT_FOUND.code(), RoutedMembers.SKIPPED, 0L
                        )
                    );
                }
                result = body.asBytesFuture().thenApply(
                    ignored -> ResponseBuilder.notFound().build()
                );
            }
            return result;
        }
    }
}
