/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.client;

import com.artipie.asto.Content;
import com.artipie.http.ArtipieHttpException;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.headers.ContentLength;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * A Slice wrapper that follows HTTP redirects (301, 302, 307, 308) to different hosts.
 * <p>
 * This is needed because Vert.x's built-in follow_redirects only handles same-host redirects.
 * Many package registries (Docker Hub, npm, PyPI, Maven Central, etc.) use CDNs that return
 * cross-domain redirects for artifact downloads.
 * <p>
 * Usage:
 * <pre>
 * Slice remote = new RedirectFollowingSlice(
 *     new AuthClientSlice(new UriClientSlice(clients, uri), auth),
 *     clients
 * );
 * </pre>
 *
 * @since 1.0
 */
public final class RedirectFollowingSlice implements Slice {

    /**
     * Maximum number of redirects to follow.
     */
    private static final int MAX_REDIRECTS = 5;

    /**
     * The wrapped slice for the original host.
     */
    private final Slice origin;

    /**
     * Client slices for creating connections to redirect URLs.
     */
    private final ClientSlices clients;

    /**
     * Constructor.
     *
     * @param origin The original slice to wrap.
     * @param clients Client slices for following redirects to different hosts.
     */
    public RedirectFollowingSlice(final Slice origin, final ClientSlices clients) {
        this.origin = origin;
        this.clients = clients;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return this.responseWithRedirects(this.origin, line, headers, body, 0);
    }

    /**
     * Send request and follow redirects if needed.
     *
     * @param slice The slice to send request to.
     * @param line Request line.
     * @param headers Request headers.
     * @param body Request body.
     * @param redirectCount Current redirect count.
     * @return Response future.
     */
    private CompletableFuture<Response> responseWithRedirects(
        final Slice slice,
        final RequestLine line,
        final Headers headers,
        final Content body,
        final int redirectCount
    ) {
        return slice.response(line, headers, body)
            .thenCompose(response -> this.handleResponse(response, line.method(), redirectCount));
    }

    /**
     * Handle response, following redirects if needed.
     *
     * @param response The HTTP response.
     * @param method Original request method.
     * @param redirectCount Current redirect count.
     * @return Response future.
     */
    private CompletableFuture<Response> handleResponse(
        final Response response,
        final RqMethod method,
        final int redirectCount
    ) {
        final RsStatus status = response.status();
        // If not a redirect, return as-is
        if (!status.redirection()) {
            return CompletableFuture.completedFuture(response);
        }
        // Don't follow redirects for non-GET/HEAD methods (per HTTP spec)
        if (method != RqMethod.GET && method != RqMethod.HEAD) {
            return CompletableFuture.completedFuture(response);
        }
        // CRITICAL: Consume body before following redirect to prevent connection leak
        return response.body().asBytesFuture()
            .thenCompose(ignored -> this.followRedirect(response, redirectCount));
    }

    /**
     * Follow a redirect response.
     *
     * @param response The redirect response.
     * @param redirectCount Current redirect count.
     * @return Response future.
     */
    private CompletableFuture<Response> followRedirect(
        final Response response,
        final int redirectCount
    ) {
        if (redirectCount >= MAX_REDIRECTS) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.internalError()
                    .textBody("Too many redirects (max " + MAX_REDIRECTS + ")")
                    .build()
            );
        }
        // Extract Location header
        final String location = response.headers()
            .find("Location")
            .stream()
            .findFirst()
            .map(Header::getValue)
            .orElse(null);
        if (location == null || location.isEmpty()) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.internalError()
                    .textBody("Redirect without Location header")
                    .build()
            );
        }
        try {
            final URI redirectUri = new URI(location);
            final Slice redirectSlice = this.createSliceForUri(redirectUri);
            final String redirectPath = redirectUri.getRawPath() +
                (redirectUri.getRawQuery() != null ? "?" + redirectUri.getRawQuery() : "");
            return this.responseWithRedirects(
                redirectSlice,
                new RequestLine(RqMethod.GET, redirectPath),
                Headers.EMPTY,
                Content.EMPTY,
                redirectCount + 1
            );
        } catch (final Exception ex) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.internalError()
                    .textBody("Invalid redirect URL: " + location + " - " + ex.getMessage())
                    .build()
            );
        }
    }

    /**
     * Create a Slice for the given URI.
     *
     * @param uri The URI to connect to.
     * @return A Slice for making requests to the URI's host.
     */
    private Slice createSliceForUri(final URI uri) {
        final String scheme = uri.getScheme().toLowerCase();
        final String host = uri.getHost();
        final int port = uri.getPort();
        if ("https".equals(scheme)) {
            return port > 0 ? this.clients.https(host, port) : this.clients.https(host);
        } else {
            return port > 0 ? this.clients.http(host, port) : this.clients.http(host);
        }
    }
}
