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
import com.jcabi.log.Logger;

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
        Logger.info(this, "Created Composer group with %d repositories", this.repositories.size());
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        Logger.info(this, "Composer group request: %s", line.uri().getPath());
        return this.tryRepositories(0, line, headers, body);
    }

    private CompletableFuture<Response> tryRepositories(
        final int index,
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        if (index >= this.repositories.size()) {
            Logger.warn(this, "No repository in group could serve: %s", line.uri().getPath());
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }

        final Slice repo = this.repositories.get(index);
        return repo.response(line, headers, body).thenCompose(response -> {
            if (response.status().success()) {
                Logger.info(
                    this,
                    "Repository %d served request successfully: %s",
                    index,
                    line.uri().getPath()
                );
                return CompletableFuture.completedFuture(response);
            }
            Logger.debug(
                this,
                "Repository %d failed (status %s), trying next: %s",
                index,
                response.status(),
                line.uri().getPath()
            );
            return this.tryRepositories(index + 1, line, headers, body);
        });
    }
}
