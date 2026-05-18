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
package com.auto1.pantera.http.client.jetty;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.LogSanitizer;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.trace.TraceHeaders;
import com.auto1.pantera.metrics.MicrometerMetrics;
import org.apache.logging.log4j.ThreadContext;
import io.reactivex.Flowable;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.Callback;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ClientSlices implementation using Jetty HTTP client as back-end.
 * <a href="https://eclipse.dev/jetty/documentation/jetty-12/programming-guide/index.html#pg-client-http-non-blocking">Docs</a>
 *
 * <p>Multiple distinct error sites in this class (request creation,
 * response parsing, body streaming, error propagation, etc.) — each
 * is a distinct failure mode that needs its own diagnostic context.
 * See audit/aggressive-items.md (Tier 4 B7 duplicate-error bucket).
 */
final class JettyClientSlice implements Slice {

    /**
     * HTTP client.
     */
    private final HttpClient client;

    /**
     * Secure connection flag.
     */
    private final boolean secure;

    /**
     * Host name.
     */
    private final String host;

    /**
     * Port.
     */
    private final int port;

    /**
     * Max time in milliseconds to wait for connection acquisition.
     */
    private final long acquireTimeoutMillis;


    /**
     * @param client HTTP client.
     * @param secure Secure connection flag.
     * @param host Host name.
     * @param port Port.
     */
    JettyClientSlice(
        HttpClient client,
        boolean secure,
        String host,
        int port,
        long acquireTimeoutMillis
    ) {
        this.client = client;
        this.secure = secure;
        this.host = host;
        this.port = port;
        this.acquireTimeoutMillis = acquireTimeoutMillis;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, com.auto1.pantera.asto.Content body
    ) {
        final Request request = this.buildRequest(headers, line);
        final CompletableFuture<Response> res = new CompletableFuture<>();
        // M1 (Finding #8): outbound observability. Snapshot caller_tag and
        // repo_name from the calling thread's MDC BEFORE we hand off to
        // Jetty's async machinery — once the request.send callback fires we
        // may be on a Jetty-internal thread that does not carry our
        // ThreadContext. The recorded values are then closed-over by the
        // callback below. See analysis/03-findings.md finding #8 + the M1
        // entry in analysis/plan/v1/PLAN.md.
        final String callerTag = nullToDefault(
            ThreadContext.get("caller.tag"), "foreground"
        );
        final String repoName = nullToDefault(
            ThreadContext.get("repository.name"), "unknown"
        );
        final long requestStartNanos = System.nanoTime();
        // Response-body publisher is constructed inside the
        // onResponseContentSource callback below — Jetty owns the
        // Content.Source lifecycle and only exposes it once headers
        // have been parsed. The JettyContentSourcePublisher bridges
        // it to Reactive Streams with proper backpressure
        // (replaces the old UnicastProcessor + StreamingDemander).
        // Mutable holder so we can complete the publisher with an
        // empty body in the no-body / failure paths below.
        final java.util.concurrent.atomic.AtomicReference<JettyContentSourcePublisher>
            responseBody = new java.util.concurrent.atomic.AtomicReference<>();
        if (line.method() != RqMethod.HEAD) {
            final AsyncRequestContent async = new AsyncRequestContent(); // NOPMD CloseResource - lifecycle owned by Jetty request; closed via Flowable.doFinally(async::close)
            Flowable.fromPublisher(body)
                .doOnError(async::fail)
                .doOnCancel(
                    () -> async.fail(new CancellationException("Request body cancelled"))
                )
                .doFinally(async::close)
                .subscribe(
                    buf -> async.write(buf, Callback.NOOP),
                    throwable -> EcsLogger.error("com.auto1.pantera.http.client")
                        .message("Failed to stream HTTP request body")
                        .eventCategory("web")
                        .eventAction("http_request_body")
                        .eventOutcome("failure")
                        .error(throwable)
                        .field("log.source", "application")
                        .log()
                );
            request.body(async);
        }
        // Record ALPN-negotiated protocol exactly once per upstream response.
        // onResponseBegin fires when the response status line is received,
        // BEFORE any body chunks or completion callbacks — this gives a
        // single, race-free counter tick for every response that arrives.
        request.onResponseBegin(JettyClientSlice::recordHttp2Negotiation);
        request.onResponseContentSource(
                (response, source) -> {
                    // Bridge Jetty's Content.Source to a Reactive Streams
                    // Publisher with proper backpressure. The downstream
                    // subscriber's request(n) flows back to the H2 layer:
                    // we only release chunks (which triggers WINDOW_UPDATE)
                    // after copying them out, and we only demand new
                    // chunks while downstream still wants data.
                    final RsStatus status = RsStatus.byCode(response.getStatus());
                    final Headers respHeaders = toHeaders(response.getHeaders());
                    final Headers sanitizedRespHeaders = LogSanitizer.sanitizeHeaders(respHeaders);
                    EcsLogger.debug("com.auto1.pantera.http.client")
                        .message("Received HTTP response headers (streaming body)")
                        .eventCategory("web")
                        .eventAction("http_response_receive")
                        .field("http.response.status_code", response.getStatus())
                        .field("http.response.headers", sanitizedRespHeaders.toString())
                        .field("log.source", "http")
                        .log();
                    final JettyContentSourcePublisher publisher =
                        new JettyContentSourcePublisher(source, response);
                    responseBody.set(publisher);
                    // Start the eager pre-drain on this I/O thread
                    // BEFORE delivering the response: the bridge copies
                    // each chunk into a heap buffer and releases the
                    // pooled Jetty buffer immediately, which is what
                    // lets HTTP/1.1 keep-alive reclaim the connection
                    // even when downstream never subscribes. By the
                    // time {@code res.complete} fires below, the
                    // staging buffer already holds whatever bytes were
                    // ready on the wire, and continuing chunks are
                    // pulled via {@link Content.Source#demand} on this
                    // same I/O thread.
                    publisher.primeOnIoThread();
                    res.complete(
                        ResponseBuilder.from(status)
                            .headers(respHeaders)
                            .body(publisher)
                            .build()
                    );
                    // Safety net for callers that never subscribe to the
                    // body publisher. Pool buffers are already released
                    // by {@link #primeOnIoThread} — this just frees the
                    // staged heap copies and prevents a late subscriber
                    // from racing against the discard. 5 ms is much
                    // longer than the typical synchronous-subscriber
                    // turnaround (microseconds), so blocking consumers
                    // (test patterns that do {@code .get().body().asBytes()})
                    // win the CAS first; only abandoned bodies fall
                    // through to the discard.
                    JettyClientSlice.this.client.getScheduler().schedule(
                        publisher::discardIfUnsubscribed,
                        5, TimeUnit.MILLISECONDS
                    );
                }
        );
        final Headers sanitizedHeaders = LogSanitizer.sanitizeHeaders(toHeaders(request.getHeaders()));
        EcsLogger.debug("com.auto1.pantera.http.client")
            .message("Sending HTTP request")
            .eventCategory("web")
            .eventAction("http_request_send")
            .field("http.request.method", request.getMethod())
            .field("url.domain", request.getHost())
            .field("url.port", request.getPort())
            .field("url.path", LogSanitizer.sanitizeUrl(request.getPath()))
            .field("http.version", request.getVersion().toString())
            .field("http.request.headers", sanitizedHeaders.toString())
            .field("log.source", "http")
            .log();
        request.send(
                result -> {
                    if (result.getFailure() == null) {
                        // For responses where onResponseContentSource never fired
                        // (empty body, HEAD, etc.), complete here with empty body.
                        // If already completed by onResponseContentSource, this is a no-op
                        // — the JettyContentSourcePublisher handles its own
                        // completion via Jetty's source.read returning isLast.
                        if (res.complete(
                            ResponseBuilder.from(
                                RsStatus.byCode(result.getResponse().getStatus())
                            )
                            .headers(toHeaders(result.getResponse().getHeaders()))
                            .body(Flowable.empty())
                            .build()
                        )) {
                            EcsLogger.debug("com.auto1.pantera.http.client")
                                .message("Received HTTP response (no body)")
                                .eventCategory("web")
                                .eventAction("http_response_receive")
                                .field("http.response.status_code",
                                    result.getResponse().getStatus())
                                .field("log.source", "http")
                                .log();
                        }
                        // Pool-buffer release is handled by
                        // {@link JettyContentSourcePublisher#primeOnIoThread}
                        // during {@code onResponseContentSource} — by
                        // the time we reach here the source is already
                        // drained, so there is nothing to discard from
                        // the success path. The scheduled fallback
                        // covers the case where the caller never
                        // subscribes (clears staged heap copies).
                        // M1 outbound metric: response received, bucket by status.
                        recordOutboundMetric(
                            callerTag, repoName, requestStartNanos,
                            result.getResponse().getStatus(), null
                        );
                    } else {
                        final Throwable failure = result.getFailure();
                        // Idle-close is a normal connection-lifecycle event
                        // (Jetty HTTP client 30s idle timeout firing on an
                        // otherwise-healthy upstream). Downgrade to DEBUG so
                        // it stops counting as a request failure in the logs
                        // (v2.1.4 WI-00, forensic §1.7 F4.4).
                        if (isIdleTimeout(failure)) {
                            EcsLogger.debug("com.auto1.pantera.http.client")
                                .message("HTTP client connection closed by idle timeout")
                                .eventCategory("web")
                                .eventAction("http_idle_close")
                                .error(failure)
                                .field("log.source", "application")
                                .log();
                        } else {
                            EcsLogger.error("com.auto1.pantera.http.client")
                                .message("HTTP request failed")
                                .eventCategory("web")
                                .eventAction("http_request_send")
                                .eventOutcome("failure")
                                .error(failure)
                                .field("log.source", "application")
                                .log();
                        }
                        // If a body publisher was created but never
                        // subscribed (edge: send-side failure after
                        // onResponseContentSource fired), discard its
                        // buffered chunks so we don't leak. Jetty's
                        // source.fail() also propagates the failure
                        // to any future subscriber; the discard guard
                        // just makes sure release() runs even if no
                        // one ever subscribes.
                        final JettyContentSourcePublisher pub = responseBody.get();
                        if (pub != null) {
                            pub.discardIfUnsubscribed();
                        }
                        res.completeExceptionally(failure);
                        // M1 outbound metric: request failed, bucket by exception.
                        recordOutboundMetric(
                            callerTag, repoName, requestStartNanos, -1, failure
                        );
                    }
                }
        );
        return res;
    }

    /**
     * M1 (Finding #8) — emit {@code pantera_upstream_requests_total} +
     * {@code pantera_proxy_429_total} for this outbound call.
     *
     * <p>Single funnel through the metric API so every outbound request
     * is counted exactly once, with caller_tag attribution and outcome
     * bucketing. Idempotent if MicrometerMetrics is uninitialised (e.g.
     * tests bootstrap without the registry).</p>
     *
     * @param callerTag         caller_tag snapshot taken before
     *     {@code request.send()} (the Jetty callback may run on a
     *     thread that does not carry our ThreadContext).
     * @param repoName          repo_name snapshot, same rationale.
     * @param requestStartNanos start time captured before send.
     * @param statusCode        upstream HTTP status, or {@code -1} on
     *     failure.
     * @param failure           upstream failure, or {@code null} on success.
     */
    private void recordOutboundMetric(
        final String callerTag,
        final String repoName,
        final long requestStartNanos,
        final int statusCode,
        final Throwable failure
    ) {
        if (!MicrometerMetrics.isInitialized()) {
            return;
        }
        final long durationMillis =
            (System.nanoTime() - requestStartNanos) / 1_000_000L;
        final String outcome = (failure == null)
            ? MicrometerMetrics.outcomeBucket(statusCode)
            : MicrometerMetrics.outcomeFromFailure(failure);
        MicrometerMetrics.getInstance().recordOutboundRequest(
            this.host, callerTag, outcome, durationMillis
        );
        if (statusCode == 429) {
            MicrometerMetrics.getInstance().recordUpstream429(this.host, repoName);
        }
    }

    /** Return {@code value} if non-null, otherwise {@code fallback}. */
    private static String nullToDefault(final String value, final String fallback) {
        return value == null ? fallback : value;
    }

    /**
     * Convert Jetty HttpFields to Pantera Headers.
     *
     * <p>When Jetty auto-decodes a gzip/deflate/br response body via its registered
     * {@code ContentDecoder.Factory} (default behaviour), the decoded (plain) bytes are
     * streamed through the processor while the original {@code Content-Encoding} header
     * is still present in {@code response.getHeaders()}. This creates a header/body
     * mismatch: the body is plain bytes but the header claims it is compressed.
     * Clients that trust the header will attempt to inflate the plain bytes and fail
     * with {@code Z_DATA_ERROR: zlib: incorrect header check}.
     *
     * <p>Fix: detect the presence of a decoded transfer encoding and strip both
     * {@code Content-Encoding} and {@code Content-Length} (which refers to the
     * compressed size, no longer valid for the decoded body) from the returned headers.
     */
    private static Headers toHeaders(final HttpFields fields) {
        final boolean decoded = fields.stream()
            .anyMatch(f -> f.is("Content-Encoding")
                && isDecodedEncoding(f.getValue()));
        if (!decoded) {
            return new Headers(
                fields.stream()
                    .map(f -> new Header(f.getName(), f.getValue()))
                    .toList()
            );
        }
        return new Headers(
            fields.stream()
                .filter(f -> !f.is("Content-Encoding") && !f.is("Content-Length"))
                .map(f -> new Header(f.getName(), f.getValue()))
                .toList()
        );
    }

    /**
     * Returns true if the encoding value is one that Jetty auto-decodes by default.
     * @param value Content-Encoding header value
     * @return True for gzip, deflate, br, x-gzip
     */
    private static boolean isDecodedEncoding(final String value) {
        final String lower = value.toLowerCase(Locale.ROOT).trim();
        return lower.contains("gzip") || lower.contains("deflate") || lower.contains("br");
    }

    /**
     * Builds jetty basic request from Pantera request line and headers.
     * @param headers Headers
     * @param req Pantera request line
     * @return Jetty request
     */
    private Request buildRequest(Headers headers, RequestLine req) {
        final String scheme = this.secure ? "https" : "http";
        final URI uri = req.uri();
        // Build the upstream URL by concatenating the raw (already-encoded)
        // path and query straight off the inbound URI and handing the
        // result to Jetty as a {@link URI}, not a {@link String}.
        //
        // Two over-encoding traps avoided here:
        //
        // 1. Apache {@code org.apache.hc.core5.net.URIBuilder} percent-
        //    encodes every sub-delim (including {@code @}, {@code :},
        //    {@code $}, {@code +}, {@code ,}, {@code ;}, {@code =},
        //    {@code !}, {@code *}, {@code (}, {@code )}, {@code '}) that
        //    RFC 3986 §3.3 lists as valid {@code pchar}. The over-encoded
        //    {@code /<module>/%40v/<version>.zip} caused
        //    {@code proxy.golang.org} to RST_STREAM mid-body on the
        //    {@code .zip} fetch (observed 2026-05-18 on
        //    {@code go.uber.org/multierr@v1.10.0}), which GOPROXY clients
        //    surface as a fatal "module download failed".
        //
        // 2. Jetty's own {@link HttpClient#newRequest(String)} re-parses
        //    the URL and re-encodes some sub-delims (notably {@code $},
        //    breaking Packagist v2 sha-pinned URLs). The
        //    {@link HttpClient#newRequest(URI)} overload trusts the URI
        //    as already canonical — building it via {@link URI#create}
        //    on a string we control keeps the raw form intact.
        final StringBuilder url = new StringBuilder()
            .append(scheme).append("://").append(this.host);
        if (this.port > 0) {
            url.append(':').append(this.port);
        }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            url.append(uri.getRawPath());
        } else {
            url.append('/');
        }
        if (uri.getRawQuery() != null) {
            url.append('?').append(uri.getRawQuery());
        }
        final Request request = this.client.newRequest(URI.create(url.toString()))
            .method(req.method().value());
        if (this.acquireTimeoutMillis > 0) {
            request.timeout(this.acquireTimeoutMillis, TimeUnit.MILLISECONDS);
        }
        // Per-request idle timeout, lifted from the HttpClient's configured
        // value. The client-level setIdleTimeout only catches *connection*
        // idleness, but under H2 a connection is rarely idle — sibling
        // streams keep it busy while one specific stream wedges (no further
        // DATA frames, no terminal RST). Pantera v2.2.0 saw exactly that
        // shape on cold Maven Central walks: headers arrived in <1 s,
        // body bytes never resumed, and {@link Request#timeout} did not
        // abort. Applying the same idle value per request makes Jetty
        // abort the stuck stream while sibling streams continue.
        final long idleMs = this.client.getIdleTimeout();
        if (idleMs > 0) {
            request.idleTimeout(idleMs, TimeUnit.MILLISECONDS);
        }
        // Inject B3 + W3C trace propagation headers from the current MDC,
        // so every upstream call from any adapter (maven, npm, docker,
        // composer, files, go, pypi, helm, debian, gem, hex, ...) carries
        // the request's trace context. No-op when MDC has no trace.id.
        final Headers withTrace = TraceHeaders.inject(headers);
        for (Header header : withTrace) {
            request.headers(mutable -> mutable.add(header.getKey(), header.getValue()));
        }
        return request;
    }

/**
     * Increment {@code pantera_http2_negotiated_total{upstream_host,version}}
     * for an upstream response, using ALPN canonical names for the version
     * label ({@code "h2"} for HTTP/2, {@code "http/1.1"} for HTTP/1.1).
     *
     * <p>Jetty's {@link HttpVersion} enum stringifies as {@code "HTTP/2.0"}
     * and {@code "HTTP/1.1"}, but the v2.2.0 perf-pack metric spec and
     * standard Prometheus dashboards use the ALPN identifiers, so we map
     * here.
     *
     * <p>No-op when {@link MicrometerMetrics} is not initialized (e.g. in
     * unit tests that don't bring up the full metrics stack).
     *
     * @param response the Jetty client response (non-null on success path)
     */
    private static void recordHttp2Negotiation(
        final org.eclipse.jetty.client.Response response
    ) {
        if (!MicrometerMetrics.isInitialized()) {
            return;
        }
        final HttpVersion version = response.getVersion();
        final String label;
        if (version == HttpVersion.HTTP_2) {
            label = "h2";
        } else if (version == HttpVersion.HTTP_1_1) {
            label = "http/1.1";
        } else {
            // HTTP/1.0, HTTP/3, or unknown — pass through Jetty's canonical
            // string so we still see distribution rather than dropping it.
            label = version == null ? "unknown" : version.asString();
        }
        final String host = response.getRequest().getURI().getHost();
        MicrometerMetrics.getInstance().recordHttp2Negotiation(
            host == null ? "unknown" : host, label
        );
    }

    /**
     * Return {@code true} iff the failure is Jetty's "Idle timeout expired:
     * N/N ms" (a {@link TimeoutException} emitted when a connection goes
     * idle and the 30s Jetty-client idle timeout fires). This is a normal
     * connection-lifecycle signal, not a request failure, and callers log
     * it at DEBUG rather than ERROR.
     *
     * @param failure The throwable from {@code result.getFailure()}
     * @return {@code true} if this is an idle-timeout close
     */
    private static boolean isIdleTimeout(final Throwable failure) {
        if (failure == null) {
            return false;
        }
        Throwable cursor = failure;
        // Walk the cause chain — Jetty may wrap the TimeoutException
        for (int hops = 0; cursor != null && hops < 5; hops = hops + 1) {
            if (cursor instanceof TimeoutException) {
                final String msg = cursor.getMessage();
                if (msg != null && msg.contains("Idle timeout expired")) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
