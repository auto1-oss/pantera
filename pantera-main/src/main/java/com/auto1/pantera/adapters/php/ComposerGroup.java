/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.adapters.php;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.http.log.EcsLogger;

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
        EcsLogger.debug("com.auto1.pantera.composer")
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
        EcsLogger.debug("com.auto1.pantera.composer")
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
            EcsLogger.warn("com.auto1.pantera.composer")
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
                EcsLogger.debug("com.auto1.pantera.composer")
                    .message("Repository served request successfully (index: " + index + ")")
                    .eventCategory("http")
                    .eventAction("group_request")
                    .eventOutcome("success")
                    .field("url.path", line.uri().getPath())
                    .log();
                return CompletableFuture.completedFuture(response);
            }
            EcsLogger.debug("com.auto1.pantera.composer")
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
