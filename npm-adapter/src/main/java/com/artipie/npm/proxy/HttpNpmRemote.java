/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.proxy;

import com.artipie.asto.Content;
import com.artipie.http.ArtipieHttpException;
import com.artipie.http.Headers;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.Slice;
import com.artipie.http.headers.ContentType;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqHeaders;
import com.artipie.http.rq.RqMethod;
import com.artipie.asto.rx.RxFuture;
import com.artipie.npm.misc.DateTimeNowStr;
import com.artipie.npm.proxy.json.CachedContent;
import com.artipie.npm.proxy.model.NpmAsset;
import com.artipie.npm.proxy.model.NpmPackage;
import io.reactivex.Maybe;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * Base NPM Remote client implementation. It calls remote NPM repository
 * to download NPM packages and assets. It uses underlying Vertx Web Client inside
 * and works in Rx-way.
 */
public final class HttpNpmRemote implements NpmRemote {

    /**
     * Origin client slice.
     */
    private final Slice origin;

    /**
     * @param origin Client slice
     */
    public HttpNpmRemote(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public Maybe<NpmPackage> loadPackage(final String name) {
        // Use non-blocking RxFuture.maybe instead of blocking Maybe.fromFuture
        return RxFuture.maybe(
            this.performRemoteRequest(name).thenCompose(
                pair -> pair.getKey().asStringFuture().thenApply(
                    str -> {
                        // Transform to cached format (strip upstream URLs)
                        // PERFORMANCE: Use valueString() instead of value().toString()
                        // This avoids an extra JSON parse/serialize cycle
                        final String cachedContent = new CachedContent(str, name).valueString();
                        return new NpmPackage(
                            name,
                            cachedContent,
                            HttpNpmRemote.lastModifiedOrNow(pair.getValue()),
                            OffsetDateTime.now()
                        );
                    }
                )
            )
        ).onErrorResumeNext(
            throwable -> {
                // Distinguish between true 404s and transient errors so the
                // negative cache only stores real "not found" responses.
                // Other errors (timeouts, connection issues) are propagated so
                // they are not cached as 404.
                if (HttpNpmRemote.isNotFoundError(throwable)) {
                    EcsLogger.debug("com.artipie.npm")
                        .message("Package not found upstream (404)")
                        .eventCategory("repository")
                        .eventAction("get_package")
                        .eventOutcome("not_found")
                        .field("package.name", name)
                        .log();
                    return Maybe.empty();
                }
                // For transient errors, log and re-throw to prevent negative cache poisoning
                EcsLogger.error("com.artipie.npm")
                    .message("Error occurred when process get package call")
                    .eventCategory("repository")
                    .eventAction("get_package")
                    .eventOutcome("failure")
                    .field("package.name", name)
                    .error(throwable)
                    .log();
                return Maybe.error(throwable);
            }
        );
    }

    @Override
    public Maybe<NpmAsset> loadAsset(final String path, final Path tmp) {
        // Use non-blocking RxFuture.maybe instead of blocking Maybe.fromFuture
        return RxFuture.maybe(
            this.performRemoteRequest(path).thenApply(
                pair -> new NpmAsset(
                    path,
                    pair.getKey(),
                    HttpNpmRemote.lastModifiedOrNow(pair.getValue()),
                    HttpNpmRemote.contentType(pair.getValue())
                )
            )
        ).onErrorResumeNext(
            throwable -> {
                // Distinguish between true 404s and transient errors so the
                // negative cache only stores real "not found" responses.
                if (HttpNpmRemote.isNotFoundError(throwable)) {
                    EcsLogger.debug("com.artipie.npm")
                        .message("Asset not found upstream (404)")
                        .eventCategory("repository")
                        .eventAction("get_asset")
                        .eventOutcome("not_found")
                        .field("package.path", path)
                        .log();
                    return Maybe.empty();
                }
                // For transient errors, log and re-throw to prevent negative cache poisoning
                EcsLogger.error("com.artipie.npm")
                    .message("Error occurred when process get asset call")
                    .eventCategory("repository")
                    .eventAction("get_asset")
                    .eventOutcome("failure")
                    .field("package.path", path)
                    .error(throwable)
                    .log();
                return Maybe.error(throwable);
            }
        );
    }

    @Override
    public void close() {
        //does nothing
    }

    /**
     * Performs request to remote and returns remote body and headers in CompletableFuture.
     * @param name Asset name
     * @return Completable action with content and headers
     */
    private CompletableFuture<Pair<Content, Headers>> performRemoteRequest(final String name) {
        // URL-encode the package name for scoped packages like @authn8/mcp-server -> @authn8%2fmcp-server
        // The npm registry expects the slash within scoped package names to be URL-encoded
        final String encodedName = encodePackageName(name);
        return this.origin.response(
            new RequestLine(RqMethod.GET, String.format("/%s", encodedName)),
            Headers.EMPTY, Content.EMPTY
        ).thenCompose(response -> {
            if (response.status().success()) {
                return CompletableFuture.completedFuture(
                    new ImmutablePair<>(response.body(), response.headers())
                );
            }
            // Consume error response body to prevent Vert.x request leak
            return response.body().asBytesFuture().thenCompose(ignored ->
                CompletableFuture.failedFuture(new ArtipieHttpException(response.status()))
            );
        });
    }

    /**
     * Tries to get header {@code Last-Modified} from remote response
     * or returns current time.
     * @param headers Remote headers
     * @return Time value.
     */
    private static String lastModifiedOrNow(final Headers headers) {
        final RqHeaders hdr = new RqHeaders(headers, "Last-Modified");
        String res = new DateTimeNowStr().value();
        if (!hdr.isEmpty()) {
            res = hdr.get(0);
        }
        return res;
    }

    /**
     * Tries to get header {@code ContentType} from remote response
     * or returns {@code application/octet-stream}.
     * @param headers Remote headers
     * @return Content type value
     */
    private static String contentType(final Headers headers) {
        final RqHeaders hdr = new RqHeaders(headers, ContentType.NAME);
        String res = "application/octet-stream";
        if (!hdr.isEmpty()) {
            res = hdr.get(0);
        }
        return res;
    }

    /**
     * URL-encode package name for upstream requests.
     * For scoped packages like @authn8/mcp-server, encodes slash as %2F.
     * The @ symbol is kept as-is since it's valid in URLs.
     *
     * @param name Package name (e.g., "lodash" or "@authn8/mcp-server")
     * @return URL-encoded package name for upstream request
     */
    private static String encodePackageName(final String name) {
        if (name.startsWith("@") && name.contains("/")) {
            // Scoped package: @scope/name -> @scope%2Fname
            final int slashIndex = name.indexOf('/');
            final String scope = name.substring(0, slashIndex);
            final String pkgName = name.substring(slashIndex + 1);
            return scope + "%2F" + pkgName;
        }
        // Non-scoped package: return as-is
        return name;
    }

    /**
     * Check if the error represents a true 404 Not Found response from upstream.
     * This is used to distinguish between actual "package not found" vs transient errors
     * (timeouts, connection errors, etc.) that should NOT be cached in negative cache.
     *
     * @param throwable The error to check
     * @return True if this is a 404 Not Found error, false for other errors
     */
    private static boolean isNotFoundError(final Throwable throwable) {
        Throwable cause = throwable;
        // Unwrap CompletionException if present
        if (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        // Check if it's an ArtipieHttpException with 404 status
        if (cause instanceof ArtipieHttpException) {
            final ArtipieHttpException httpEx = (ArtipieHttpException) cause;
            return httpEx.status().code() == 404;
        }
        return false;
    }
}
