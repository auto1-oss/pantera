/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.metrics.MicrometerMetrics;
import io.reactivex.Flowable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Slice decorator that records repository-level HTTP metrics.
 * Adds repo_name and repo_type labels to pantera_http_requests_total and
 * pantera_http_request_duration_seconds metrics.
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

                // CRITICAL FIX: Do NOT wrap response body with Flowable.
                // Response bodies from storage are often Content.OneTime which can only
                // be subscribed once. Wrapping causes double subscription.
                // Use Content-Length header for download size tracking instead.
                if (MicrometerMetrics.isInitialized() && isSuccessStatus(response.status())) {
                    response.headers().values("Content-Length").stream()
                        .findFirst()
                        .ifPresent(contentLength -> {
                            try {
                                final long respBytes = Long.parseLong(contentLength);
                                if (respBytes > 0) {
                                    MicrometerMetrics.getInstance().recordRepoBytesDownloaded(
                                        RepoMetricsSlice.this.repoName,
                                        RepoMetricsSlice.this.repoType,
                                        respBytes
                                    );
                                }
                            } catch (final NumberFormatException ex) {
                                EcsLogger.debug("com.auto1.pantera.metrics")
                                    .message("Invalid Content-Length header value")
                                    .error(ex)
                                    .log();
                            }
                        });
                }

                // Pass response through unchanged - no body wrapping
                return response;
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

