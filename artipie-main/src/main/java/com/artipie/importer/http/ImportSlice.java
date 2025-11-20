/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.http;

import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.ResponseException;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.importer.ImportRequest;
import com.artipie.importer.ImportResult;
import com.artipie.http.log.EcsLogger;
import com.artipie.importer.ImportService;
import com.artipie.importer.ImportStatus;
import com.artipie.importer.api.DigestType;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonObjectBuilder;

/**
 * HTTP slice exposing the global import endpoint.
 *
 * @since 1.0
 */
public final class ImportSlice implements Slice {

    /**
     * Import service.
     */
    private final ImportService service;

    /**
     * Ctor.
     *
     * @param service Import service
     */
    public ImportSlice(final ImportService service) {
        this.service = service;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final com.artipie.asto.Content body
    ) {
        final ImportRequest request;
        try {
            request = ImportRequest.parse(line, headers);
        } catch (final ResponseException error) {
            return CompletableFuture.completedFuture(error.response());
        } catch (final Exception error) {
            EcsLogger.error("com.artipie.importer")
                .message("Failed to parse import request")
                .eventCategory("api")
                .eventAction("import_artifact")
                .eventOutcome("failure")
                .field("url.path", line.uri().getPath())
                .error(error)
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.badRequest(error).build()
            );
        }
        try {
            return this.service.importArtifact(request, body)
                .thenApply(ImportSlice::toResponse)
                .exceptionally(throwable -> {
                    final Throwable cause = unwrap(throwable);
                    if (cause instanceof ResponseException rex) {
                        return rex.response();
                    }
                    EcsLogger.error("com.artipie.importer")
                        .message("Import processing failed")
                        .eventCategory("repository")
                        .eventAction("import_artifact")
                        .eventOutcome("failure")
                        .error(cause)
                        .log();
                    return ResponseBuilder.internalError(cause).build();
                }).toCompletableFuture();
        } catch (final ResponseException rex) {
            return CompletableFuture.completedFuture(rex.response());
        } catch (final Exception ex) {
            EcsLogger.error("com.artipie.importer")
                .message("Import processing failed")
                .eventCategory("repository")
                .eventAction("import_artifact")
                .eventOutcome("failure")
                .error(ex)
                .log();
            return CompletableFuture.completedFuture(ResponseBuilder.internalError(ex).build());
        }
    }

    /**
     * Convert result to HTTP response.
     *
     * @param result Import result
     * @return HTTP response
     */
    private static Response toResponse(final ImportResult result) {
        final ResponseBuilder builder = switch (result.status()) {
            case CREATED -> ResponseBuilder.created();
            case ALREADY_PRESENT -> ResponseBuilder.ok();
            case CHECKSUM_MISMATCH -> ResponseBuilder.from(RsStatus.CONFLICT);
            case INVALID_METADATA -> ResponseBuilder.badRequest();
            case RETRY_LATER -> ResponseBuilder.unavailable();
            case FAILED -> ResponseBuilder.internalError();
        };
        final JsonObjectBuilder digests = Json.createObjectBuilder();
        result.digests().forEach(
            (type, value) -> digests.add(alias(type), value == null ? "" : value)
        );
        final JsonObjectBuilder payload = Json.createObjectBuilder()
            .add("status", result.status().name())
            .add("message", result.message())
            .add("size", result.size())
            .add("digests", digests);
        result.quarantineKey().ifPresent(key -> payload.add("quarantineKey", key));
        return builder.jsonBody(payload.build()).build();
    }

    /**
     * Map digest type to json field alias.
     *
     * @param type Digest type
     * @return Alias
     */
    private static String alias(final DigestType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Unwrap completion exception.
     *
     * @param throwable Throwable
     * @return Root cause
     */
    private static Throwable unwrap(final Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
