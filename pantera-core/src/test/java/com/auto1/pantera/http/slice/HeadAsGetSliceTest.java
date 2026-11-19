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
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Test for {@link HeadAsGetSlice}.
 *
 * @since 2.2.12
 */
final class HeadAsGetSliceTest {

    /**
     * Body of the GET answer.
     */
    private static final byte[] DATA = "archive bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void answersHeadWithStatusAndHeadersOfGetAndNoBody() throws Exception {
        final List<RqMethod> asked = new CopyOnWriteArrayList<>();
        final Response response = new HeadAsGetSlice(
            (line, headers, body) -> {
                asked.add(line.method());
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header("Content-Type", "application/zip")
                        .body(HeadAsGetSliceTest.DATA)
                        .build()
                );
            }
        ).response(
            new RequestLine(RqMethod.HEAD, "/artifacts/acme/lib/1.0/acme-lib-1.0.zip?x=1"),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "status of the GET", response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Content-Length of the GET body",
            response.headers().values("Content-Length"),
            new IsEqual<>(List.of(String.valueOf(HeadAsGetSliceTest.DATA.length)))
        );
        MatcherAssert.assertThat(
            "the other headers of the GET",
            response.headers().values("Content-Type"), new IsEqual<>(List.of("application/zip"))
        );
        MatcherAssert.assertThat(
            "no body",
            response.body().asBytesFuture().get(30, TimeUnit.SECONDS).length, new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "the wrapped slice was asked with GET",
            asked, new IsEqual<>(List.of(RqMethod.GET))
        );
    }

    @Test
    void drainsTheBodyOfTheGetAnswer() throws Exception {
        // An unread body leaks its buffers and, for a storage read, keeps
        // the connection or file open.
        final AtomicBoolean drained = new AtomicBoolean();
        new HeadAsGetSlice(
            new SliceSimple(
                ResponseBuilder.ok().body(
                    new Content.From(
                        Optional.of((long) HeadAsGetSliceTest.DATA.length),
                        Flowable.just(ByteBuffer.wrap(HeadAsGetSliceTest.DATA))
                            .doOnComplete(() -> drained.set(true))
                    )
                ).build()
            )
        ).response(
            new RequestLine(RqMethod.HEAD, "/chart-1.0.tgz"), Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(drained.get(), new IsEqual<>(true));
    }

    @Test
    void datesHeadWithTheTimeTheGetBodyCarries() throws Exception {
        // The content a storage returns knows when it was last modified;
        // dropping the body must not lose that.
        final Response response = new HeadAsGetSlice(
            new SliceSimple(
                ResponseBuilder.ok().body(
                    new Content.From(
                        Optional.of((long) HeadAsGetSliceTest.DATA.length),
                        Flowable.just(ByteBuffer.wrap(HeadAsGetSliceTest.DATA)),
                        Optional.of(Instant.parse("2018-06-17T18:35:49Z"))
                    )
                ).build()
            )
        ).response(
            new RequestLine(RqMethod.HEAD, "/chart-1.0.tgz"), Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            response.headers().values("Last-Modified"),
            new IsEqual<>(List.of("Sun, 17 Jun 2018 18:35:49 GMT"))
        );
    }

    @Test
    void keepsTheDateTheGetAnswerAlreadyHas() throws Exception {
        final Response response = new HeadAsGetSlice(
            new SliceSimple(
                ResponseBuilder.ok()
                    .header("Last-Modified", "Wed, 10 Apr 2024 14:25:45 GMT")
                    .body(
                        new Content.From(
                            Optional.empty(),
                            Flowable.just(ByteBuffer.wrap(HeadAsGetSliceTest.DATA)),
                            Optional.of(Instant.parse("2018-06-17T18:35:49Z"))
                        )
                    ).build()
            )
        ).response(
            new RequestLine(RqMethod.HEAD, "/chart-1.0.tgz"), Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            response.headers().values("Last-Modified"),
            new IsEqual<>(List.of("Wed, 10 Apr 2024 14:25:45 GMT"))
        );
    }

    @Test
    void relaysNotFoundOfGet() throws Exception {
        MatcherAssert.assertThat(
            new HeadAsGetSlice(new SliceSimple(ResponseBuilder.notFound().build())).response(
                new RequestLine(RqMethod.HEAD, "/missing.zip"), Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS).status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    @ParameterizedTest
    @EnumSource(value = RqMethod.class, names = {"GET", "PUT", "POST", "DELETE"})
    void passesOtherMethodsThroughUntouched(final RqMethod method) throws Exception {
        final List<RqMethod> asked = new CopyOnWriteArrayList<>();
        final Slice origin = (line, headers, body) -> {
            asked.add(line.method());
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(HeadAsGetSliceTest.DATA).build()
            );
        };
        final Response response = new HeadAsGetSlice(origin).response(
            new RequestLine(method, "/chart-1.0.tgz"), Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the body is the origin's",
            response.body().asBytesFuture().get(30, TimeUnit.SECONDS),
            new IsEqual<>(HeadAsGetSliceTest.DATA)
        );
        MatcherAssert.assertThat(
            "the method is unchanged", asked, new IsEqual<>(List.of(method))
        );
    }
}
