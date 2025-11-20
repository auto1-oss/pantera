/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.adapters.php;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.settings.repo.RepoConfig;
import com.artipie.http.log.EcsLogger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Composer group repository.
 * Tries multiple repositories in order (local first, then proxy).
 *
 * @since 1.0
 */
public final class ComposerGroup implements Slice {

    private final List<Slice> repositories;

    /**
     * @param local Local repository slice
     * @param proxy Proxy repository slice
     */
    public ComposerGroup(final Slice local, final Slice proxy) {
        this(List.of(local, proxy));
    }
    
    /**
     * @param slices Repository slices (varargs)
     */
    public ComposerGroup(final Slice... slices) {
        this(List.of(slices));
    }
    
    /**
     * @param repositories List of repository slices
     */
    public ComposerGroup(final List<Slice> repositories) {
        this.repositories = repositories;
        EcsLogger.debug("com.artipie.composer")
            .message("Created Composer group (" + this.repositories.size() + " repositories)")
            .eventCategory("repository")
            .eventAction("group_create")
            .log();
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        EcsLogger.debug("com.artipie.composer")
            .message("Composer group request")
            .eventCategory("http")
            .eventAction("group_request")
            .field("url.path", line.uri().getPath())
            .log();
        return this.tryRepositories(0, line, headers, body);
    }

    private CompletableFuture<Response> tryRepositories(
        final int index,
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        if (index >= this.repositories.size()) {
            EcsLogger.warn("com.artipie.composer")
                .message("No repository in group could serve request")
                .eventCategory("http")
                .eventAction("group_request")
                .eventOutcome("failure")
                .field("url.path", line.uri().getPath())
                .log();
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }

        final Slice repo = this.repositories.get(index);
        return repo.response(line, headers, body).thenCompose(response -> {
            if (response.status().success()) {
                EcsLogger.debug("com.artipie.composer")
                    .message("Repository served request successfully (index: " + index + ")")
                    .eventCategory("http")
                    .eventAction("group_request")
                    .eventOutcome("success")
                    .field("url.path", line.uri().getPath())
                    .log();
                return CompletableFuture.completedFuture(response);
            }
            EcsLogger.debug("com.artipie.composer")
                .message("Repository failed, trying next (index: " + index + ")")
                .eventCategory("http")
                .eventAction("group_request")
                .eventOutcome("failure")
                .field("http.response.status_code", response.status().code())
                .field("url.path", line.uri().getPath())
                .log();
            return this.tryRepositories(index + 1, line, headers, body);
        });
    }
}
