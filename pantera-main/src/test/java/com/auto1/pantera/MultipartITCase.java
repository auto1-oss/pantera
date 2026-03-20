/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentDisposition;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.multipart.RqMultipart;
import com.auto1.pantera.vertx.VertxSliceServer;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.vertx.reactivex.core.Vertx;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Integration tests for multipart feature.
 */
final class MultipartITCase {

    private Vertx vertx;

    private VertxSliceServer server;

    private int port;

    private SliceContainer container;

    @BeforeEach
    void init() {
        this.vertx = Vertx.vertx();
        this.container = new SliceContainer();
        this.server = new VertxSliceServer(this.vertx, this.container);
        this.port = this.server.start();
    }

    @AfterEach
    void tearDown() {
        this.server.stop();
        this.server.close();
        this.vertx.close();
    }

    @Test
    @SuppressWarnings("PMD.AvoidDuplicateLiterals")
    void parseMultiparRequest() throws Exception {
        final AtomicReference<String> result = new AtomicReference<>();
        this.container.deploy(
            (line, headers, body) ->
                new Content.From(
                    Flowable.fromPublisher(
                        new RqMultipart(headers, body).inspect(
                            (part, sink) -> {
                                final ContentDisposition cds =
                                    new ContentDisposition(part.headers());
                                if (cds.fieldName().equals("content")) {
                                    sink.accept(part);
                                } else {
                                    sink.ignore(part);
                                }
                                final CompletableFuture<Void> res = new CompletableFuture<>();
                                res.complete(null);
                                return res;
                            }
                        )
                    ).flatMap(part -> part)
                ).asStringFuture().thenAccept(result::set).thenApply(
                    none -> ResponseBuilder.ok().build()
                )
        );
        final String data = "hello-multipart";
        try (CloseableHttpClient cli = HttpClients.createDefault()) {
            final HttpPost post = new HttpPost(String.format("http://localhost:%d/", this.port));
            post.setEntity(
                MultipartEntityBuilder.create()
                    .addTextBody("name", "test-data")
                    .addTextBody("content", data)
                    .addTextBody("foo", "bar")
                    .build()
            );
            try (CloseableHttpResponse rsp = cli.execute(post)) {
                MatcherAssert.assertThat(
                    "code should be 200", rsp.getCode(), Matchers.equalTo(200)
                );
            }
        }
        MatcherAssert.assertThat(
            "content data should be parsed correctly", result.get(), Matchers.equalTo(data)
        );
    }

    @Test
    void parseBigMultiparRequest() throws Exception {
        final AtomicReference<String> result = new AtomicReference<>();
        this.container.deploy(
            (line, headers, body) ->
                new Content.From(
                    Flowable.fromPublisher(
                        new RqMultipart(headers, body).inspect(
                            (part, sink) -> {
                                final ContentDisposition cds =
                                    new ContentDisposition(part.headers());
                                if (cds.fieldName().equals("content")) {
                                    sink.accept(part);
                                } else {
                                    sink.ignore(part);
                                }
                                final CompletableFuture<Void> res = new CompletableFuture<>();
                                res.complete(null);
                                return res;
                            }
                        )
                    ).flatMap(part -> part)
                ).asStringFuture().thenAccept(result::set).thenApply(
                    none -> ResponseBuilder.ok().build()
                )
        );
        final byte[] buf = testData();
        try (CloseableHttpClient cli = HttpClients.createDefault()) {
            final HttpPost post = new HttpPost(String.format("http://localhost:%d/", this.port));
            post.setEntity(
                MultipartEntityBuilder.create()
                    .addTextBody("name", "test-data")
                    .addBinaryBody("content", buf)
                    .addTextBody("foo", "bar")
                    .build()
            );
            try (CloseableHttpResponse rsp = cli.execute(post)) {
                MatcherAssert.assertThat(
                    "code should be 200", rsp.getCode(), Matchers.equalTo(200)
                );
            }
        }
        MatcherAssert.assertThat(
            "content data should be parsed correctly",
            result.get(),
            Matchers.equalTo(new String(buf, StandardCharsets.US_ASCII))
        );
    }

    @Test
    void saveMultipartToFile(@TempDir final Path path) throws Exception {
        this.container.deploy(
            (line, headers, body) ->
                Flowable.fromPublisher(
                    new RqMultipart(headers, body).inspect(
                        (part, sink) -> {
                            final ContentDisposition cds =
                                new ContentDisposition(part.headers());
                            if (cds.fieldName().equals("content")) {
                                sink.accept(part);
                            } else {
                                sink.ignore(part);
                            }
                            return CompletableFuture.completedFuture(null);
                        }
                    )
                ).flatMapSingle(
                    part -> com.auto1.pantera.asto.rx.RxFuture.single(
                        new FileStorage(path).save(
                            new Key.From(new ContentDisposition(part.headers()).fileName()),
                            new Content.From(part)
                        ).thenApply(none -> 0)
                    )
                    ).toList()
                    .to(SingleInterop.get())
                    .toCompletableFuture()
                    .thenApply(none -> ResponseBuilder.ok().build())
        );
        final byte[] buf = testData();
        final String filename = "data.bin";
        try (CloseableHttpClient cli = HttpClients.createDefault()) {
            final HttpPost post = new HttpPost(String.format("http://localhost:%d/", this.port));
            post.setEntity(
                MultipartEntityBuilder.create()
                    .addTextBody("name", "test-data")
                    .addBinaryBody("content", buf, ContentType.APPLICATION_OCTET_STREAM, filename)
                    .addTextBody("foo", "bar")
                    .build()
            );
            try (CloseableHttpResponse rsp = cli.execute(post)) {
                MatcherAssert.assertThat(
                    "code should be 200", rsp.getCode(), Matchers.equalTo(200)
                );
            }
        }
        MatcherAssert.assertThat(
            "content data should be save correctly",
            Files.readAllBytes(path.resolve(filename)),
            Matchers.equalTo(buf)
        );
    }

    /**
     * Create new test data buffer for payload.
     *
     * @return Byte array
     */
    private static byte[] testData() {
        final byte[] buf = new byte[34816];
        final byte[] chunk = "0123456789ABCDEF\n".getBytes(StandardCharsets.US_ASCII);
        for (int pos = 0; pos < buf.length; pos += chunk.length) {
            System.arraycopy(chunk, 0, buf, pos, chunk.length);
        }
        return buf;
    }

    /**
     * Container for slice with dynamic deployment.
     */
    private static final class SliceContainer implements Slice {

        /**
         * Target slice.
         */
        private volatile Slice target;

        @Override
        public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            return target != null ? target.response(line, headers, body)
                : CompletableFuture.completedFuture(ResponseBuilder.unavailable()
                .textBody("target is not set").build());

        }

        /**
         * Deploy slice to container.
         * @param slice Deployment
         */
        void deploy(final Slice slice) {
            this.target = slice;
        }
    }
}
