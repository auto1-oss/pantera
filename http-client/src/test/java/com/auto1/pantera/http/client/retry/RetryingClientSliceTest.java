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
package com.auto1.pantera.http.client.retry;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import io.reactivex.Flowable;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.MapMessage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsSame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test for {@link RetryingClientSlice}.
 *
 * @since 2.2.12
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class RetryingClientSliceTest {

    /**
     * Request line.
     */
    private static final RequestLine GET = new RequestLine(
        RqMethod.GET, "/v2/beats/filebeat/blobs/sha256:0e8a0913c581?ns=docker.elastic.co"
    );

    @AfterEach
    void tearDown() {
        ThreadContext.clearMap();
    }

    @Test
    void sendsGetAgainAfterTheConnectionWasClosed() throws Exception {
        final Upstream upstream = new Upstream(new EOFException("early EOF"), RsStatus.OK);
        final Headers headers = Headers.from(new Header("Authorization", "Bearer abc"));
        final Response response = new RetryingClientSlice(upstream, "docker.elastic.co")
            .response(RetryingClientSliceTest.GET, headers, Content.EMPTY)
            .get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the answer of the second attempt", response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the same request both times",
            upstream.lines(),
            new IsEqual<>(List.of(RetryingClientSliceTest.GET, RetryingClientSliceTest.GET))
        );
        MatcherAssert.assertThat(
            "with the same headers",
            upstream.headers(), new IsEqual<>(List.of(headers, headers))
        );
    }

    @Test
    void sendsHeadAgainAfterTheConnectionWasClosed() throws Exception {
        final Upstream upstream = new Upstream(new EOFException("early EOF"), RsStatus.OK);
        new RetryingClientSlice(upstream, "docker.elastic.co").response(
            new RequestLine(RqMethod.HEAD, "/v2/beats/filebeat/blobs/sha256:0e8a"),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(upstream.calls(), new IsEqual<>(2));
    }

    @ParameterizedTest
    @MethodSource("closedConnections")
    void sendsAgainForEveryFormOfAClosedConnection(final Throwable failure) throws Exception {
        final Upstream upstream = new Upstream(failure, RsStatus.OK);
        new RetryingClientSlice(upstream, "repo1.maven.org")
            .response(RetryingClientSliceTest.GET, Headers.EMPTY, Content.EMPTY)
            .get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(upstream.calls(), new IsEqual<>(2));
    }

    @ParameterizedTest
    @MethodSource("otherFailures")
    void doesNotSendAgainAfterAnotherFailure(final Throwable failure) {
        // A timeout has already cost its full wait and a second attempt
        // doubles it; a refused connection, an unknown host or a failed TLS
        // handshake fails the same way again.
        final Upstream upstream = new Upstream(failure, RsStatus.OK);
        final ExecutionException thrown = Assertions.assertThrows(
            ExecutionException.class,
            () -> new RetryingClientSlice(upstream, "repo1.maven.org")
                .response(RetryingClientSliceTest.GET, Headers.EMPTY, Content.EMPTY)
                .get(30, TimeUnit.SECONDS),
            "the request fails"
        );
        MatcherAssert.assertThat(
            "with the failure of the only attempt", thrown.getCause(), new IsSame<>(failure)
        );
        MatcherAssert.assertThat("one attempt", upstream.calls(), new IsEqual<>(1));
    }

    @ParameterizedTest
    @EnumSource(value = RqMethod.class, names = {"POST", "PUT", "DELETE", "PATCH"})
    void doesNotSendAgainARequestThatChangesState(final RqMethod method) {
        // The upstream may have acted on it before the connection went away.
        final Upstream upstream = new Upstream(new EOFException("early EOF"), RsStatus.OK);
        Assertions.assertThrows(
            ExecutionException.class,
            () -> new RetryingClientSlice(upstream, "registry.npmjs.org").response(
                new RequestLine(method, "/-/npm/v1/security/advisories/bulk"),
                Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS),
            "the request fails"
        );
        MatcherAssert.assertThat("one attempt", upstream.calls(), new IsEqual<>(1));
    }

    @Test
    void doesNotSendAgainARequestThatCarriesABody() {
        // The body is a stream that the first attempt has consumed.
        final Upstream upstream = new Upstream(new EOFException("early EOF"), RsStatus.OK);
        Assertions.assertThrows(
            ExecutionException.class,
            () -> new RetryingClientSlice(upstream, "registry.npmjs.org").response(
                RetryingClientSliceTest.GET, Headers.EMPTY,
                new Content.From("query".getBytes(StandardCharsets.UTF_8))
            ).get(30, TimeUnit.SECONDS),
            "the request fails"
        );
        MatcherAssert.assertThat("one attempt", upstream.calls(), new IsEqual<>(1));
    }

    @Test
    void doesNotSendAgainARequestWhoseBodyHasNoDeclaredSize() {
        final Upstream upstream = new Upstream(new EOFException("early EOF"), RsStatus.OK);
        Assertions.assertThrows(
            ExecutionException.class,
            () -> new RetryingClientSlice(upstream, "registry.npmjs.org").response(
                RetryingClientSliceTest.GET, Headers.EMPTY,
                new Content.From(Optional.empty(), Flowable.empty())
            ).get(30, TimeUnit.SECONDS),
            "the request fails"
        );
        MatcherAssert.assertThat("one attempt", upstream.calls(), new IsEqual<>(1));
    }

    @Test
    void givesUpAfterTwoMoreAttempts() {
        final EOFException last = new EOFException("third");
        final Upstream upstream = new Upstream(
            new EOFException("first"), new EOFException("second"), last, RsStatus.OK
        );
        final ExecutionException thrown = Assertions.assertThrows(
            ExecutionException.class,
            () -> new RetryingClientSlice(upstream, "docker.elastic.co")
                .response(RetryingClientSliceTest.GET, Headers.EMPTY, Content.EMPTY)
                .get(30, TimeUnit.SECONDS),
            "the request fails"
        );
        MatcherAssert.assertThat(
            "with the failure of the last attempt", thrown.getCause(), new IsSame<>(last)
        );
        MatcherAssert.assertThat("three attempts", upstream.calls(), new IsEqual<>(3));
    }

    @Test
    void returnsAnErrorStatusOfTheUpstreamWithoutSendingAgain() throws Exception {
        // An answer is an answer: repeating on 5xx is the caller's and the
        // circuit breaker's decision, not this slice's.
        final Upstream upstream = new Upstream(RsStatus.BAD_GATEWAY, RsStatus.OK);
        final Response response = new RetryingClientSlice(upstream, "docker.elastic.co")
            .response(RetryingClientSliceTest.GET, Headers.EMPTY, Content.EMPTY)
            .get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the upstream's answer", response.status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat("one attempt", upstream.calls(), new IsEqual<>(1));
    }

    @Test
    void failsWhenTheSecondAttemptCannotEvenBeSent() {
        final IllegalStateException broken = new IllegalStateException("client stopped");
        final List<RequestLine> seen = new CopyOnWriteArrayList<>();
        final Slice upstream = (line, headers, body) -> {
            seen.add(line);
            if (seen.size() > 1) {
                throw broken;
            }
            return CompletableFuture.failedFuture(new EOFException("early EOF"));
        };
        final ExecutionException thrown = Assertions.assertThrows(
            ExecutionException.class,
            () -> new RetryingClientSlice(upstream, "docker.elastic.co")
                .response(RetryingClientSliceTest.GET, Headers.EMPTY, Content.EMPTY)
                .get(30, TimeUnit.SECONDS),
            "the request fails instead of never completing"
        );
        MatcherAssert.assertThat(thrown.getCause(), new IsSame<>(broken));
    }

    @Test
    void sendsAgainWithTheLoggingContextOfTheCaller() throws Exception {
        // The failure arrives on a thread of the HTTP client, which does not
        // carry the request's context: the second attempt would go out
        // without the trace headers and be counted against no repository.
        final List<String> traces = new CopyOnWriteArrayList<>();
        final CompletableFuture<Response> first = new CompletableFuture<>();
        final Slice upstream = (line, headers, body) -> {
            traces.add(String.valueOf(ThreadContext.get("trace.id")));
            final CompletableFuture<Response> answer;
            if (traces.size() == 1) {
                answer = first;
            } else {
                answer = CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            }
            return answer;
        };
        ThreadContext.put("trace.id", "4bf92f3577b34da6");
        final CompletableFuture<Response> response =
            new RetryingClientSlice(upstream, "docker.elastic.co")
                .response(RetryingClientSliceTest.GET, Headers.EMPTY, Content.EMPTY);
        ThreadContext.clearMap();
        final Thread client = new Thread(
            () -> {
                ThreadContext.put("trace.id", "another-request");
                first.completeExceptionally(new EOFException("early EOF"));
            },
            "http-client"
        );
        client.start();
        client.join(TimeUnit.SECONDS.toMillis(30));
        response.get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            traces, new IsEqual<>(List.of("4bf92f3577b34da6", "4bf92f3577b34da6"))
        );
    }

    @Test
    void logsEachRepeatedAttempt() throws Exception {
        final Upstream upstream = new Upstream(
            new EOFException("first"), new EOFException("second"), RsStatus.OK
        );
        try (Capture capture = Capture.of("com.auto1.pantera.http.client")) {
            new RetryingClientSlice(upstream, "docker.elastic.co")
                .response(RetryingClientSliceTest.GET, Headers.EMPTY, Content.EMPTY)
                .get(30, TimeUnit.SECONDS);
            final List<Map<String, Object>> records = capture.action("http_request_retry");
            MatcherAssert.assertThat(
                "one record per repeated attempt", records.size(), new IsEqual<>(2)
            );
            final Map<String, Object> record = records.get(0);
            MatcherAssert.assertThat(
                "why", record.get("event.reason"), new IsEqual<>("connection_closed")
            );
            MatcherAssert.assertThat(
                "which upstream",
                record.get("destination.address"), new IsEqual<>("docker.elastic.co")
            );
            MatcherAssert.assertThat(
                "which request",
                String.format("%s %s", record.get("http.request.method"), record.get("url.path")),
                new IsEqual<>("GET /v2/beats/filebeat/blobs/sha256:0e8a0913c581")
            );
            MatcherAssert.assertThat(
                "an application record",
                record.get("log.source"), new IsEqual<>("application")
            );
        }
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<Throwable> closedConnections() {
        return Stream.of(
            new EOFException("HttpConnectionOverHTTP@52fa41e9::SslEndPoint[OPEN,fill=-]"),
            new ClosedChannelException(),
            new IOException("Connection reset by peer"),
            new IOException("Broken pipe"),
            new CompletionException(new EOFException("early EOF"))
        );
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<Throwable> otherFailures() {
        return Stream.of(
            new TimeoutException("Idle timeout expired: 60000/60000 ms"),
            new TimeoutException("Connection acquire timeout 30000 ms elapsed"),
            new ConnectException("Connection refused"),
            new UnknownHostException("nowhere.example"),
            new SSLHandshakeException("PKIX path building failed"),
            new IOException("disk full"),
            new IllegalStateException("unmapped status")
        );
    }

    /**
     * Upstream that answers each call with the next scripted outcome: a
     * failure, or a response of the given status.
     */
    private static final class Upstream implements Slice {

        /**
         * Outcomes, in order.
         */
        private final List<Object> outcomes;

        /**
         * Request lines received.
         */
        private final List<RequestLine> received;

        /**
         * Headers received.
         */
        private final List<Headers> sent;

        Upstream(final Object... outcomes) {
            this.outcomes = List.of(outcomes);
            this.received = new CopyOnWriteArrayList<>();
            this.sent = new CopyOnWriteArrayList<>();
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final Object outcome = this.outcomes.get(this.received.size());
            this.received.add(line);
            this.sent.add(headers);
            final CompletableFuture<Response> answer;
            if (outcome instanceof Throwable failure) {
                answer = CompletableFuture.failedFuture(failure);
            } else {
                answer = CompletableFuture.completedFuture(
                    ResponseBuilder.from((RsStatus) outcome).build()
                );
            }
            return answer;
        }

        int calls() {
            return this.received.size();
        }

        List<RequestLine> lines() {
            return List.copyOf(this.received);
        }

        List<Headers> headers() {
            return List.copyOf(this.sent);
        }
    }

    /**
     * Appender capturing the structured records of one logger.
     */
    private static final class Capture extends AbstractAppender implements AutoCloseable {

        /**
         * Records.
         */
        private final List<Map<String, Object>> events;

        /**
         * Logger.
         */
        private final Logger logger;

        /**
         * Level before.
         */
        private final Level before;

        private Capture(final Logger logger) {
            super("RetryingClientSliceTest", null, null, true, Property.EMPTY_ARRAY);
            this.events = Collections.synchronizedList(new ArrayList<>());
            this.logger = logger;
            this.before = logger.getLevel();
        }

        static Capture of(final String name) {
            final Capture capture = new Capture((Logger) LogManager.getLogger(name));
            capture.start();
            capture.logger.addAppender(capture);
            capture.logger.setLevel(Level.DEBUG);
            return capture;
        }

        List<Map<String, Object>> action(final String action) {
            final List<Map<String, Object>> out = new ArrayList<>();
            synchronized (this.events) {
                for (final Map<String, Object> event : this.events) {
                    if (action.equals(String.valueOf(event.get("event.action")))) {
                        out.add(event);
                    }
                }
            }
            return out;
        }

        @Override
        public void append(final LogEvent event) {
            if (event.getMessage() instanceof MapMessage<?, ?> map) {
                final Map<String, Object> data = new HashMap<>();
                map.getData().forEach((key, value) -> data.put(String.valueOf(key), value));
                this.events.add(data);
            }
        }

        @Override
        public void close() {
            this.logger.removeAppender(this);
            this.logger.setLevel(this.before);
            this.stop();
        }
    }
}
