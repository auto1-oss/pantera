/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.slice;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import org.apache.hc.core5.net.URIBuilder;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice that removes the first part from the request URI.
 * <p>
 * For example {@code GET http://www.w3.org/pub/WWW/TheProject.html HTTP/1.1}
 * would be {@code GET http://www.w3.org/WWW/TheProject.html HTTP/1.1}.
 * <p>
 * The full path will be available as the value of {@code X-FullPath} header.
 */
public final class TrimPathSlice implements Slice {

    /**
     * Full path header name.
     */
    private static final String HDR_FULL_PATH = "X-FullPath";

    /**
     * Delegate slice.
     */
    private final Slice slice;

    /**
     * Pattern to trim.
     */
    private final Pattern ptn;

    /**
     * Trim URI path by first hit of path param.
     * @param slice Origin slice
     * @param path Path to trim
     */
    public TrimPathSlice(final Slice slice, final String path) {
        this(
            slice,
            Pattern.compile(String.format("^/(?:%s)(\\/.*)?", TrimPathSlice.normalized(path)))
        );
    }

    /**
     * Trim URI path by pattern.
     *
     * @param slice Origin slice
     * @param ptn Path to trim
     */
    public TrimPathSlice(final Slice slice, final Pattern ptn) {
        this.slice = slice;
        this.ptn = ptn;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final URI uri = line.uri();
        final String full = uri.getPath();
        final Matcher matcher = this.ptn.matcher(full);
        final boolean recursion = !new RqHeaders(headers, TrimPathSlice.HDR_FULL_PATH).isEmpty();
        if (matcher.matches() && recursion) {
            // Recursion detected - pass through without trimming
            org.slf4j.LoggerFactory.getLogger("com.auto1.pantera.http.slice.TrimPathSlice")
                .debug("TrimPathSlice recursion: path={}, pattern={}", full, this.ptn);
            return this.slice.response(line, headers, body);
        }
        if (matcher.matches() && !recursion) {
            URI respUri;
            try {
                respUri = new URIBuilder(uri)
                    .setPath(asPath(matcher.group(1)))
                    .build();
            } catch (URISyntaxException e) {
                throw new PanteraException(e);
            }
            final String trimmedPath = respUri.getPath();
            org.slf4j.LoggerFactory.getLogger("com.auto1.pantera.http.slice.TrimPathSlice")
                .debug("TrimPathSlice trim: {} -> {} (pattern={})", full, trimmedPath, this.ptn);
            return this.slice.response(
                new RequestLine(line.method(), respUri, line.version()),
                headers.copy().add(new Header(TrimPathSlice.HDR_FULL_PATH, full)),
                body
            );
        }
        // Consume request body to prevent Vert.x request leak
        org.slf4j.LoggerFactory.getLogger("com.auto1.pantera.http.slice.TrimPathSlice")
            .warn("TrimPathSlice NO MATCH: path={}, pattern={}", full, this.ptn);
        return body.asBytesFuture().thenApply(ignored ->
            ResponseBuilder.internalError()
                .textBody(String.format("Request path %s was not matched to %s", full, this.ptn))
                .build()
        );
    }

    /**
     * Normalize path: remove whitespaces and slash chars.
     * @param path Path
     * @return Normalized path
     */
    private static String normalized(final String path) {
        final String clear = Objects.requireNonNull(path).trim();
        if (clear.isEmpty()) {
            return "";
        }
        if (clear.charAt(0) == '/') {
            return normalized(clear.substring(1));
        }
        if (clear.charAt(clear.length() - 1) == '/') {
            return normalized(clear.substring(0, clear.length() - 1));
        }
        return clear;
    }

    /**
     * Convert matched string to valid path.
     * @param result Result of matching
     * @return Path string
     */
    private static String asPath(final String result) {
        if (result == null || result.isEmpty()) {
            return "/";
        }
        String path = result;
        if (path.charAt(0) != '/') {
            path = '/' + path;
        }
        return path.replaceAll("/+", "/");
    }
}
