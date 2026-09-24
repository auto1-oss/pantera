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
import com.auto1.pantera.docker.error.InvalidRepoNameException;
import com.auto1.pantera.docker.error.PaginationNumberInvalidException;
import com.auto1.pantera.docker.misc.ImageRepositoryName;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonException;
import javax.json.JsonReader;
import javax.json.JsonString;

/**
 * Docker group slice: the registry listings a group answers itself.
 *
 * <p>{@code GET /v2/<group>/_catalog} is the union of every member's
 * catalog, renamed from {@code <member>/<image>} to {@code <group>/<image>}
 * — the name a client pulls the image by through the group — and paged
 * over the merged list. The generic first-wins walk relayed one member's
 * catalog as is, under that member's names, and left out the rest.</p>
 *
 * <p>Every other request goes to the {@link GroupResolver} walk; a
 * {@code Link: rel="next"} header on its answer (a full tags page) is
 * rewritten from the member's path to the group's, so a client following
 * the link stays on the group.</p>
 *
 * <p>The catalog merge honours the group-member circuit breaker like the
 * walk: an open-circuit member is skipped, an upstream-circuit-open marker
 * is a skip without conviction, and genuine failures are recorded. Members
 * that fail leave a partial catalog; when no member contributes and one was
 * unavailable, the group answers 503 + Retry-After.</p>
 *
 * @since 2.2.9
 */
public final class DockerGroupSlice implements Slice {

    /**
     * Group-relative catalog path.
     */
    private static final String CATALOG = "/_catalog";

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.group";

    /**
     * A Link header value naming a repository-routed registry path.
     */
    private static final Pattern LINK = Pattern.compile("^</v2/([^/>]+)(/[^>]*)>(.*)$");

    /**
     * Group walk handling every other request.
     */
    private final Slice delegate;

    /**
     * Group repository name.
     */
    private final String group;

    /**
     * Flattened members in declared order.
     */
    private final List<MemberSlice> members;

    /**
     * Wiring constructor: shares the resolver's members, so both paths see
     * the same per-member breaker state.
     *
     * @param resolver Group resolver handling every other request
     */
    public DockerGroupSlice(final GroupResolver resolver) {
        this(resolver, resolver.groupName(), resolver.members());
    }

    /**
     * Primary constructor.
     *
     * @param delegate Group walk handling every other request
     * @param group Group repository name
     * @param members Flattened members in declared order
     */
    DockerGroupSlice(final Slice delegate, final String group, final List<MemberSlice> members) {
        this.delegate = delegate;
        this.group = group;
        this.members = List.copyOf(members);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        if ("GET".equals(line.method().value()) && CATALOG.equals(line.uri().getPath())) {
            return body.asBytesFuture().thenCompose(ignored -> this.catalog(line, headers));
        }
        return this.delegate.response(line, headers, body).thenApply(this::groupLinks);
    }

    /**
     * Merge the members' catalogs into one group catalog page.
     */
    private CompletableFuture<Response> catalog(final RequestLine line, final Headers headers) {
        final Pagination page;
        final Optional<String> cursor;
        try {
            page = Pagination.from(line.uri());
            cursor = this.cursor(page.last());
        } catch (final InvalidRepoNameException | PaginationNumberInvalidException ex) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.badRequest().jsonBody(ex.json()).build()
            );
        }
        final Headers forwarded = new Headers(
            headers.asList().stream()
                .filter(h -> !"X-FullPath".equalsIgnoreCase(h.getKey()))
                .toList()
        ).copy().add(new Header(EcsLoggingSlice.INTERNAL_ROUTING_HEADER, "true"));
        final List<CompletableFuture<MemberCatalog>> all = new ArrayList<>(this.members.size());
        for (final MemberSlice member : this.members) {
            all.add(this.fetch(member, cursor, page.limit(), forwarded));
        }
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new))
            .thenApply(
                done -> this.answer(
                    all.stream().map(CompletableFuture::join).toList(), page, line
                )
            );
    }

    /**
     * The image-name part of a group catalog cursor.
     *
     * @param last Cursor as sent, may be null
     * @return Image name after the group prefix, empty without a cursor
     * @throws InvalidRepoNameException When the cursor is not a group name
     */
    private Optional<String> cursor(final String last) {
        Optional<String> rest = Optional.empty();
        if (last != null) {
            final String prefix = this.group + "/";
            if (!last.startsWith(prefix) || last.length() == prefix.length()) {
                throw new InvalidRepoNameException(
                    String.format("`last` must name an image under `%s`, got `%s`", prefix, last)
                );
            }
            rest = Optional.of(ImageRepositoryName.validate(last.substring(prefix.length())));
        }
        return rest;
    }

    /**
     * One member's catalog, asked from the group cursor on.
     */
    private CompletableFuture<MemberCatalog> fetch(
        final MemberSlice member, final Optional<String> cursor, final int limit,
        final Headers headers
    ) {
        if (member.isCircuitOpen()) {
            this.metric(member, "circuit_open");
            return CompletableFuture.completedFuture(
                new MemberCatalog(Optional.empty(), Outcome.SKIPPED, member.retryAfterSeconds())
            );
        }
        final RequestLine request = member.rewritePath(
            new RequestLine(
                "GET",
                new Pagination(cursor.map(name -> member.name() + "/" + name).orElse(null), limit)
                    .uriWithPagination(CATALOG)
            )
        );
        return member.slice().response(request, headers, Content.EMPTY)
            .thenCompose(resp -> resp.body().asBytesFuture().thenApply(
                bytes -> this.classify(member, resp, bytes)
            ))
            .exceptionally(err -> this.failure(member, err));
    }

    /**
     * Classify a member's catalog response the way the group walk does.
     */
    private MemberCatalog classify(
        final MemberSlice member, final Response resp, final byte[] bytes
    ) {
        final RsStatus status = resp.status();
        final MemberCatalog res;
        if (status == RsStatus.OK) {
            final Optional<List<String>> names = this.names(member, bytes);
            if (names.isPresent()) {
                member.recordSuccess();
                this.metric(member, "success");
                res = new MemberCatalog(names, Outcome.ANSWERED, 0L);
            } else {
                res = this.failed(member, "unparseable catalog", status.code());
            }
        } else if (status.serverError()
            && !resp.headers().values(UpstreamCircuitOpenException.HEADER).isEmpty()) {
            this.metric(member, "circuit_open");
            res = new MemberCatalog(
                Optional.empty(), Outcome.SKIPPED, GroupResolver.parseRetryAfterSeconds(resp)
            );
        } else if (status.serverError()) {
            res = this.failed(member, "status", status.code());
        } else {
            // 401 / 403 / 404: the member holds nothing this caller may list.
            this.metric(member, status == RsStatus.NOT_FOUND ? "not_found" : "success");
            res = new MemberCatalog(Optional.empty(), Outcome.ANSWERED, 0L);
        }
        return res;
    }

    /**
     * A member's catalog names, renamed under the group.
     *
     * @param member Member
     * @param bytes Catalog JSON
     * @return Group names; empty when the document does not parse
     */
    private Optional<List<String>> names(final MemberSlice member, final byte[] bytes) {
        final String prefix = member.name() + "/";
        Optional<List<String>> names;
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(bytes))) {
            final JsonArray repos = reader.readObject().getJsonArray("repositories");
            names = Optional.of(
                repos == null ? List.of()
                    : repos.getValuesAs(JsonString.class).stream()
                        .map(JsonString::getString)
                        .filter(name -> name.startsWith(prefix) && name.length() > prefix.length())
                        .map(name -> this.group + "/" + name.substring(prefix.length()))
                        .toList()
            );
        } catch (final JsonException | ClassCastException ex) {
            names = Optional.empty();
        }
        return names;
    }

    /**
     * A member call that threw: a failure unless it was cancelled.
     */
    private MemberCatalog failure(final MemberSlice member, final Throwable err) {
        final Throwable cause = err.getCause() != null ? err.getCause() : err;
        final MemberCatalog res;
        if (cause instanceof CancellationException) {
            res = new MemberCatalog(Optional.empty(), Outcome.ANSWERED, 0L);
        } else {
            res = this.failed(member, cause.getMessage(), 0);
        }
        return res;
    }

    /**
     * Record and log a genuine member failure.
     */
    private MemberCatalog failed(final MemberSlice member, final String reason, final int code) {
        member.recordFailure();
        this.metric(member, "error");
        EcsLogger.warn(LOGGER)
            .message(
                "Docker group member failed to answer the catalog: " + member.name()
                    + " (" + reason + ")"
            )
            .eventCategory("web")
            .eventAction("group_docker_catalog_member_failed")
            .eventOutcome("failure")
            .field("repository.name", this.group)
            .field("url.path", CATALOG)
            .field("http.response.status_code", code)
            .field("log.source", "application")
            .log();
        return new MemberCatalog(Optional.empty(), Outcome.FAILED, 0L);
    }

    /**
     * Build the group catalog page from every member's outcome.
     */
    private Response answer(
        final List<MemberCatalog> results, final Pagination page, final RequestLine line
    ) {
        final TreeSet<String> merged = new TreeSet<>();
        boolean contributed = false;
        boolean unavailable = false;
        long retry = 0L;
        for (final MemberCatalog result : results) {
            result.names().ifPresent(merged::addAll);
            contributed |= result.names().isPresent();
            unavailable |= result.outcome() != Outcome.ANSWERED;
            retry = Math.max(retry, result.retryAfter());
        }
        final Response res;
        if (!contributed && unavailable) {
            res = this.unavailable(line, retry);
        } else {
            final List<String> names = (page.last() == null ? merged : merged.tailSet(page.last(), false))
                .stream().limit(page.limit()).toList();
            final JsonArrayBuilder repos = Json.createArrayBuilder();
            names.forEach(repos::add);
            final ResponseBuilder found = ResponseBuilder.ok()
                .jsonBody(Json.createObjectBuilder().add("repositories", repos).build());
            page.nextLink(String.format("/v2/%s%s", this.group, CATALOG), names)
                .ifPresent(link -> found.header("Link", link));
            res = found.build();
        }
        return res;
    }

    /**
     * No member could list: 503 + Retry-After, never an empty catalog.
     */
    private Response unavailable(final RequestLine line, final long hint) {
        final long retry = Math.max(5L, hint);
        EcsLogger.warn(LOGGER)
            .message(
                "All docker group members unavailable for the catalog — returning 503,"
                    + " Retry-After " + retry + "s"
            )
            .eventCategory("network")
            .eventAction("group_all_members_unavailable")
            .eventOutcome("failure")
            .field("repository.name", this.group)
            .field("url.path", line.uri().getPath())
            .field("log.source", "application")
            .log();
        return ResponseBuilder.from(RsStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", Long.toString(retry))
            .textBody("All group members are temporarily unavailable")
            .build();
    }

    /**
     * Rename a member path in the walk answer's Link headers to the group's.
     *
     * @param resp Walk answer
     * @return Answer whose Link headers point at the group
     */
    private Response groupLinks(final Response resp) {
        final List<String> links = resp.headers().values("Link");
        Response res = resp;
        if (!links.isEmpty()) {
            final Headers headers = new Headers(
                resp.headers().asList().stream()
                    .filter(h -> !"Link".equalsIgnoreCase(h.getKey()))
                    .toList()
            ).copy();
            links.forEach(link -> headers.add("Link", this.groupLink(link)));
            res = new Response(resp.status(), headers, resp.body());
        }
        return res;
    }

    /**
     * One Link value renamed from a member path to the group path.
     */
    private String groupLink(final String link) {
        final Matcher matcher = LINK.matcher(link.trim());
        String res = link;
        if (matcher.matches()
            && this.members.stream().anyMatch(m -> m.name().equals(matcher.group(1)))) {
            res = String.format(
                "</v2/%s%s>%s", this.group, matcher.group(2), matcher.group(3)
            );
        }
        return res;
    }

    /**
     * Record the per-member group metric (same series as the walk).
     */
    private void metric(final MemberSlice member, final String result) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordGroupMemberRequest(this.group, member.name(), result);
        }
    }

    /**
     * How a member took part in the merge.
     */
    private enum Outcome {
        /** Answered (with or without a catalog). */
        ANSWERED,
        /** Genuinely failed; recorded on its breaker. */
        FAILED,
        /** Skipped: its group or upstream circuit is open. */
        SKIPPED
    }

    /**
     * One member's contribution.
     *
     * @param names Group-renamed names when the member answered a catalog
     * @param outcome How the member took part
     * @param retryAfter Retry-After hint in seconds for a skipped member
     */
    private record MemberCatalog(Optional<List<String>> names, Outcome outcome, long retryAfter) {
    }
}
