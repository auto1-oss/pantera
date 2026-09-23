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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;

/**
 * Sends one read request to every member of an npm group in parallel and
 * collects each member's JSON object answer, for group endpoints whose
 * answer is the union of all members' answers (signing keys, search) rather
 * than the first member's.
 *
 * <p>A member that fails, times out, answers a non-2xx status or a body
 * that is not a JSON object contributes nothing; the failure is logged.
 * Members are addressed through their own repository slice, so the path is
 * prefixed with the member name and the caller's credentials are forwarded
 * for the member to authorize.</p>
 *
 * @since 2.2.9
 */
final class MemberFanout {

    /**
     * Per-member answer timeout in seconds.
     */
    private static final long TIMEOUT_SECONDS = 30;

    /**
     * Member repository names, in declared order.
     */
    private final List<String> names;

    /**
     * Member repository slices, same order as the names.
     */
    private final List<Slice> slices;

    /**
     * Ctor.
     * @param names Member repository names
     * @param slices Member repository slices, same order
     */
    MemberFanout(final List<String> names, final List<Slice> slices) {
        if (names.size() != slices.size()) {
            throw new IllegalArgumentException(
                String.format(
                    "Member names and slices must have same size: %d vs %d",
                    names.size(), slices.size()
                )
            );
        }
        this.names = List.copyOf(names);
        this.slices = List.copyOf(slices);
    }

    /**
     * Query every member.
     * @param line Request line as received by the group
     * @param headers Request headers
     * @return Each answering member's JSON object, in member order
     */
    CompletableFuture<List<JsonObject>> query(final RequestLine line, final Headers headers) {
        final List<CompletableFuture<Optional<JsonObject>>> answers =
            new ArrayList<>(this.names.size());
        for (int idx = 0; idx < this.names.size(); ++idx) {
            answers.add(this.member(this.names.get(idx), this.slices.get(idx), line, headers));
        }
        return CompletableFuture.allOf(answers.toArray(new CompletableFuture<?>[0])).thenApply(
            nothing -> {
                final List<JsonObject> result = new ArrayList<>(answers.size());
                answers.forEach(answer -> answer.join().ifPresent(result::add));
                return result;
            }
        );
    }

    /**
     * Query one member; never fails.
     * @param name Member name
     * @param slice Member slice
     * @param line Group request line
     * @param headers Request headers
     * @return Member's JSON object, if it answered one
     */
    private CompletableFuture<Optional<JsonObject>> member(
        final String name, final Slice slice, final RequestLine line, final Headers headers
    ) {
        CompletableFuture<Optional<JsonObject>> answer;
        try {
            answer = slice.response(
                MemberFanout.rewrite(line, name), MemberFanout.forward(headers), Content.EMPTY
            ).thenCompose(MemberFanout::json);
        } catch (final RuntimeException err) {
            answer = CompletableFuture.failedFuture(err);
        }
        return answer
            .orTimeout(MemberFanout.TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(err -> MemberFanout.failed(name, line, err));
    }

    /**
     * Parse a member response as a JSON object, draining the body.
     * @param response Member response
     * @return JSON object, if the answer was a 2xx JSON object
     */
    private static CompletableFuture<Optional<JsonObject>> json(final Response response) {
        final boolean success = response.status().success();
        return response.body().asBytesFuture().thenApply(
            bytes -> {
                Optional<JsonObject> json = Optional.empty();
                if (success) {
                    try (JsonReader reader = Json.createReader(
                        new StringReader(new String(bytes, StandardCharsets.UTF_8))
                    )) {
                        json = Optional.of(reader.readObject());
                    } catch (final JsonException | IllegalStateException err) {
                        json = Optional.empty();
                    }
                }
                return json;
            }
        );
    }

    /**
     * Log a member failure and contribute nothing.
     * @param name Member name
     * @param line Group request line
     * @param err Failure
     * @return Empty answer
     */
    private static Optional<JsonObject> failed(
        final String name, final RequestLine line, final Throwable err
    ) {
        EcsLogger.warn("com.auto1.pantera.npm")
            .message("npm group member left out of the merged answer: " + name)
            .eventCategory("web")
            .eventAction("group_member_merge")
            .eventOutcome("failure")
            .field("repository.name", name)
            .field("url.path", line.uri().getPath())
            .error(err)
            .field("log.source", "application")
            .log();
        return Optional.empty();
    }

    /**
     * Prefix the path with the member repository name, which the member's
     * path-trimming slice expects.
     * @param original Group request line
     * @param member Member name
     * @return Member request line
     */
    private static RequestLine rewrite(final RequestLine original, final String member) {
        final URI uri = original.uri();
        final String raw = uri.getRawPath();
        final StringBuilder full = new StringBuilder("/").append(member);
        if (!raw.startsWith("/")) {
            full.append('/');
        }
        full.append(raw);
        if (uri.getRawQuery() != null) {
            full.append('?').append(uri.getRawQuery());
        }
        return new RequestLine(original.method().value(), full.toString(), original.version());
    }

    /**
     * Drop the internal full-path header, which names the group path.
     * @param headers Request headers
     * @return Headers to forward
     */
    private static Headers forward(final Headers headers) {
        return new Headers(
            headers.asList().stream()
                .filter(hdr -> !"X-FullPath".equalsIgnoreCase(hdr.getKey()))
                .toList()
        );
    }
}
