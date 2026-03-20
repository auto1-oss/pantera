/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import java.util.concurrent.CompletableFuture;

/**
 * Utility methods for Response handling to prevent memory leaks.
 * 
 * <p>These utilities ensure response bodies are properly consumed
 * even when the response is discarded (e.g., 404s, race conditions).
 * This prevents Vert.x HTTP server request leaks where the active
 * request counter never decrements.</p>
 * 
 * @since 1.0
 */
public final class ResponseUtils {

    /**
     * Private constructor to prevent instantiation.
     */
    private ResponseUtils() {
    }

    /**
     * Consume response body and discard it.
     * Used when response is not needed (e.g., lost parallel race, 404 in multi-repo lookup).
     * 
     * <p><b>CRITICAL:</b> Always consume response bodies before discarding
     * to prevent Vert.x request leaks. Vert.x keeps requests "active" until
     * the body Publisher is fully consumed or canceled.</p>
     * 
     * @param response Response to consume and discard
     * @return CompletableFuture that completes when body is consumed
     */
    public static CompletableFuture<Void> consumeAndDiscard(final Response response) {
        return response.body().asBytesFuture().thenApply(ignored -> null);
    }

    /**
     * Consume response body and return a 404 response.
     * Used in error handling where we need to consume the error response
     * body before returning 404 to the client.
     * 
     * @param response Response to consume
     * @return CompletableFuture<Response> that resolves to 404
     */
    public static CompletableFuture<Response> consumeAndReturn404(final Response response) {
        return response.body().asBytesFuture().thenApply(ignored ->
            ResponseBuilder.notFound().build()
        );
    }

    /**
     * Consume response body and return a custom response.
     * Used when we need to consume the body but return a different response.
     * 
     * @param response Response whose body should be consumed
     * @param replacement Response to return after consumption
     * @return CompletableFuture<Response> that resolves to replacement
     */
    public static CompletableFuture<Response> consumeAndReturn(
        final Response response,
        final Response replacement
    ) {
        return response.body().asBytesFuture().thenApply(ignored -> replacement);
    }

    /**
     * Consume response body and throw an exception.
     * Used when converting error responses to exceptions (e.g., upstream failures).
     * 
     * <p><b>CRITICAL:</b> Must consume body BEFORE throwing exception
     * to prevent Vert.x request leaks on error paths.</p>
     * 
     * @param response Response to consume
     * @param exception Exception to throw after consumption
     * @param <T> Return type (will never actually return, always throws)
     * @return CompletableFuture that fails with exception after consuming body
     */
    public static <T> CompletableFuture<T> consumeAndFail(
        final Response response,
        final Throwable exception
    ) {
        return response.body().asBytesFuture().thenCompose(ignored ->
            CompletableFuture.failedFuture(exception)
        );
    }

    /**
     * Check if response indicates success and consume if not.
     * Common pattern for proxy implementations that only care about successful responses.
     * 
     * @param response Response to check
     * @return CompletableFuture<Boolean> - true if success (body NOT consumed),
     *         false if not success (body consumed)
     */
    public static CompletableFuture<Boolean> isSuccessOrConsume(final Response response) {
        if (response.status().success()) {
            return CompletableFuture.completedFuture(true);
        }
        return response.body().asBytesFuture().thenApply(ignored -> false);
    }

    /**
     * Consume body only if condition is true, otherwise pass through.
     * Used in conditional logic where response might be used or discarded.
     * 
     * @param response Response to potentially consume
     * @param shouldConsume Whether to consume the body
     * @return CompletableFuture<Response> - same response if not consumed, null if consumed
     */
    public static CompletableFuture<Response> consumeIf(
        final Response response,
        final boolean shouldConsume
    ) {
        if (shouldConsume) {
            return response.body().asBytesFuture().thenApply(ignored -> null);
        }
        return CompletableFuture.completedFuture(response);
    }
}
