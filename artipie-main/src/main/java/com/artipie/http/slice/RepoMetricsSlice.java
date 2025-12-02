/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.metrics.MicrometerMetrics;
import io.reactivex.Flowable;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Slice decorator that records repository-level HTTP metrics.
 * Adds repo_name and repo_type labels to artipie_http_requests_total and
 * artipie_http_request_duration_seconds metrics.
 *
 * @since 1.0
 */
public final class RepoMetricsSlice implements Slice {

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Repository type.
     */
    private final String repoType;

    /**
     * Ctor.
     *
     * @param origin Origin slice
     * @param repoName Repository name
     * @param repoType Repository type
     */
    public RepoMetricsSlice(final Slice origin, final String repoName, final String repoType) {
        this.origin = origin;
        this.repoName = repoName;
        this.repoType = repoType;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final long startTime = System.currentTimeMillis();
        final String method = line.method().value();
        final AtomicLong requestBytes = new AtomicLong(0);

        // Wrap request body to count bytes
        // CRITICAL: Use restore=true to preserve buffer position for downstream consumers
        final Content wrappedBody = new Content.From(
            body.size(),
            Flowable.fromPublisher(body)
                .doOnNext(buffer -> requestBytes.addAndGet(buffer.remaining()))
        );

        return this.origin.response(line, headers, wrappedBody)
            .thenApply(response -> {
                final long duration = System.currentTimeMillis() - startTime;
                final String statusCode = String.valueOf(response.status().code());

                // Record HTTP request metrics with repository context
                if (MicrometerMetrics.isInitialized()) {
                    MicrometerMetrics.getInstance().recordHttpRequest(
                        method,
                        statusCode,
                        duration,
                        this.repoName,
                        this.repoType
                    );

                    // Record upload traffic based on method
                    final long reqBytes = requestBytes.get();
                    if (reqBytes > 0 && isUploadMethod(method)) {
                        MicrometerMetrics.getInstance().recordRepoBytesUploaded(
                            this.repoName,
                            this.repoType,
                            reqBytes
                        );
                    }
                }

                // Wrap response body to count download bytes
                // CRITICAL: Use buffer.remaining() to avoid consuming the buffer
                final AtomicLong responseBytes = new AtomicLong(0);
                final Content wrappedResponseBody = new Content.From(
                    response.body().size(),
                    Flowable.fromPublisher(response.body())
                        .doOnNext(buffer -> responseBytes.addAndGet(buffer.remaining()))
                        .doOnComplete(() -> {
                            // Record download traffic when response completes
                            if (MicrometerMetrics.isInitialized()) {
                                final long respBytes = responseBytes.get();
                                if (respBytes > 0 && isSuccessStatus(response.status())) {
                                    MicrometerMetrics.getInstance().recordRepoBytesDownloaded(
                                        RepoMetricsSlice.this.repoName,
                                        RepoMetricsSlice.this.repoType,
                                        respBytes
                                    );
                                }
                            }
                        })
                );

                return new Response(response.status(), response.headers(), wrappedResponseBody);
            });
    }

    /**
     * Check if HTTP method is an upload operation.
     * @param method HTTP method
     * @return True if upload method
     */
    private static boolean isUploadMethod(final String method) {
        return "PUT".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method);
    }

    /**
     * Check if status indicates successful response.
     * @param status Response status
     * @return True if 2xx status
     */
    private static boolean isSuccessStatus(final RsStatus status) {
        final int code = status.code();
        return code >= 200 && code < 300;
    }
}

