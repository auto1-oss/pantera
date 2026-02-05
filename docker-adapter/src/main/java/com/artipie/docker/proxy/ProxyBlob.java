/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.proxy;

import com.artipie.asto.Content;
import com.artipie.docker.Blob;
import com.artipie.docker.Digest;
import com.artipie.http.ArtipieHttpException;
import com.artipie.http.Headers;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.headers.ContentLength;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * Proxy implementation of {@link Blob}.
 * <p>
 * Handles blob downloads from Docker registries, including following
 * 307 TEMPORARY_REDIRECT responses to CDN URLs for blob content.
 */
public final class ProxyBlob implements Blob {

    /**
     * Maximum number of redirects to follow.
     */
    private static final int MAX_REDIRECTS = 5;

    /**
     * Remote repository.
     */
    private final Slice remote;

    /**
     * Repository name.
     */
    private final String name;

    /**
     * Blob digest.
     */
    private final Digest digest;

    /**
     * Blob size.
     */
    private final long blobSize;

    /**
     * Client slices for creating new connections to redirect URLs.
     * May be null if redirect following is not supported.
     */
    private final ClientSlices clients;

    /**
     * @param remote Remote repository.
     * @param name Repository name.
     * @param digest Blob digest.
     * @param size Blob size.
     */
    public ProxyBlob(Slice remote, String name, Digest digest, long size) {
        this(remote, name, digest, size, null);
    }

    /**
     * Constructor with ClientSlices for redirect support.
     *
     * @param remote Remote repository.
     * @param name Repository name.
     * @param digest Blob digest.
     * @param size Blob size.
     * @param clients Client slices for following redirects to CDN URLs.
     */
    public ProxyBlob(Slice remote, String name, Digest digest, long size, ClientSlices clients) {
        this.remote = remote;
        this.name = name;
        this.digest = digest;
        this.blobSize = size;
        this.clients = clients;
    }

    @Override
    public Digest digest() {
        return this.digest;
    }

    @Override
    public CompletableFuture<Long> size() {
        return CompletableFuture.completedFuture(this.blobSize);
    }

    /**
     * Get blob content.
     * <p>
     * Downloads the blob from the remote registry, following redirects if needed.
     *
     * @return Content future.
     */
    @Override
    public CompletableFuture<Content> content() {
        final String blobPath = String.format("/v2/%s/blobs/%s", this.name, this.digest.string());
        return this.fetchWithRedirects(this.remote, blobPath, 0);
    }

    /**
     * Fetch content with redirect support.
     *
     * @param slice The slice to send request to.
     * @param path The request path (or full URI for redirects).
     * @param redirectCount Current redirect count.
     * @return Content future.
     */
    private CompletableFuture<Content> fetchWithRedirects(
        final Slice slice,
        final String path,
        final int redirectCount
    ) {
        return slice
            .response(new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY)
            .thenCompose(response -> this.handleResponse(response, redirectCount));
    }

    /**
     * Handle response, following redirects if needed.
     *
     * @param response The HTTP response.
     * @param redirectCount Current redirect count.
     * @return Content future.
     */
    private CompletableFuture<Content> handleResponse(
        final com.artipie.http.Response response,
        final int redirectCount
    ) {
        final RsStatus status = response.status();
        if (status == RsStatus.OK) {
            // Success - return content
            final Content res = response.headers()
                .find(ContentLength.NAME)
                .stream()
                .findFirst()
                .map(h -> Long.parseLong(h.getValue()))
                .map(val -> (Content) new Content.From(val, response.body()))
                .orElseGet(response::body);
            return CompletableFuture.completedFuture(res);
        }
        // Check for redirect status codes
        if (isRedirect(status)) {
            // CRITICAL: Consume body before following redirect to prevent request leak
            return response.body().asBytesFuture().thenCompose(
                ignored -> this.handleRedirect(response, redirectCount)
            );
        }
        // CRITICAL: Consume body even on error to prevent request leak
        return response.body().asBytesFuture().thenCompose(
            ignored -> CompletableFuture.failedFuture(
                new ArtipieHttpException(status, "Unexpected status: " + status)
            )
        );
    }

    /**
     * Handle redirect response.
     *
     * @param response The redirect response.
     * @param redirectCount Current redirect count.
     * @return Content future.
     */
    private CompletableFuture<Content> handleRedirect(
        final com.artipie.http.Response response,
        final int redirectCount
    ) {
        if (redirectCount >= MAX_REDIRECTS) {
            return CompletableFuture.failedFuture(
                new ArtipieHttpException(
                    RsStatus.INTERNAL_ERROR,
                    "Too many redirects (max " + MAX_REDIRECTS + ")"
                )
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
            return CompletableFuture.failedFuture(
                new ArtipieHttpException(
                    RsStatus.INTERNAL_ERROR,
                    "Redirect without Location header"
                )
            );
        }
        // Check if we have ClientSlices for cross-domain redirects
        if (this.clients == null) {
            return CompletableFuture.failedFuture(
                new ArtipieHttpException(
                    RsStatus.INTERNAL_ERROR,
                    "Redirect to " + location + " but no ClientSlices configured"
                )
            );
        }
        try {
            final URI redirectUri = new URI(location);
            final Slice redirectSlice = createSliceForUri(redirectUri);
            final String redirectPath = redirectUri.getRawPath() +
                (redirectUri.getRawQuery() != null ? "?" + redirectUri.getRawQuery() : "");
            return this.fetchWithRedirects(redirectSlice, redirectPath, redirectCount + 1);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                new ArtipieHttpException(
                    RsStatus.INTERNAL_ERROR,
                    "Invalid redirect URL: " + location,
                    e
                )
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

    /**
     * Check if status is a redirect.
     * Uses the built-in redirection() method which checks for 3xx status codes.
     *
     * @param status The HTTP status.
     * @return True if redirect status.
     */
    private static boolean isRedirect(final RsStatus status) {
        return status.redirection();
    }
}
