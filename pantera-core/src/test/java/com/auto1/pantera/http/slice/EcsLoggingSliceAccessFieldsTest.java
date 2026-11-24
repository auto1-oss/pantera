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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.AnsweringRepository;
import com.auto1.pantera.http.log.LogCapture;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.ThreadContext;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The access record names the repository the request was for and the
 * client that sent it. All 2.95 million 404 and 119 thousand 401 access
 * records of four production days had neither {@code repository.name} nor
 * {@code user_agent.original}: the access slice sits above the routing and
 * was built without a repository, and never read the {@code User-Agent}.
 *
 * @since 2.2.14
 */
final class EcsLoggingSliceAccessFieldsTest {

    private LogCapture capture;

    @BeforeEach
    void setUp() {
        ThreadContext.clearAll();
        this.capture = LogCapture.of("http.access");
    }

    @AfterEach
    void tearDown() {
        this.capture.close();
        ThreadContext.clearAll();
    }

    @Test
    void notFoundRecordNamesRepositoryAndUserAgent() {
        final Response sent = this.serve(
            new ReasonSlice(
                (line, headers, body) ->
                    CompletableFuture.completedFuture(ResponseBuilder.notFound().build()),
                "npm-local", "npm"
            ),
            Headers.from("User-Agent", "npm/10.8.2 node/v20.18.0 linux x64")
        );
        final Map<String, Object> record = this.record();
        MatcherAssert.assertThat(
            "the record names the repository that answered",
            record.get("repository.name"), new IsEqual<>("npm-local")
        );
        MatcherAssert.assertThat(
            "and its type",
            record.get("repository.type"), new IsEqual<>("npm")
        );
        MatcherAssert.assertThat(
            "and the client's user agent",
            record.get("user_agent.original"),
            new IsEqual<>("npm/10.8.2 node/v20.18.0 linux x64")
        );
        MatcherAssert.assertThat(
            "the repository headers stay inside Pantera",
            List.of(
                sent.headers().values(AnsweringRepository.NAME_HEADER),
                sent.headers().values(AnsweringRepository.TYPE_HEADER)
            ),
            new IsEqual<>(List.of(List.of(), List.of()))
        );
    }

    @Test
    void groupRequestIsRecordedUnderTheGroupNotItsMember() {
        final Slice member = new ReasonSlice(
            (line, headers, body) ->
                CompletableFuture.completedFuture(ResponseBuilder.unauthorized().build()),
            "maven-central", "maven-proxy"
        );
        this.serve(new ReasonSlice(member, "maven-group", "maven-group"), Headers.EMPTY);
        MatcherAssert.assertThat(
            this.record().get("repository.name"), new IsEqual<>("maven-group")
        );
    }

    @Test
    void successfulRequestIsNamedToo() {
        this.serve(
            new ReasonSlice(
                (line, headers, body) ->
                    CompletableFuture.completedFuture(ResponseBuilder.ok().build()),
                "files", "file"
            ),
            Headers.EMPTY
        );
        MatcherAssert.assertThat(
            this.record().get("repository.name"), new IsEqual<>("files")
        );
    }

    @Test
    void answerFromOutsideAnyRepositoryHasNoRepository() {
        this.serve(
            (line, headers, body) ->
                CompletableFuture.completedFuture(ResponseBuilder.notFound().build()),
            Headers.EMPTY
        );
        MatcherAssert.assertThat(
            this.record().get("repository.name"), new IsEqual<>(null)
        );
    }

    private Response serve(final Slice origin, final Headers headers) {
        return new EcsLoggingSlice(origin).response(
            new RequestLine("GET", "/npm-local/@retail/ui/-/ui-5.4.0.tgz"),
            headers, Content.EMPTY
        ).join();
    }

    private Map<String, Object> record() {
        final List<Map<String, Object>> records = this.capture.action("http_request");
        MatcherAssert.assertThat(
            "one access record per request", records.size(), new IsEqual<>(1)
        );
        return records.get(0);
    }
}
