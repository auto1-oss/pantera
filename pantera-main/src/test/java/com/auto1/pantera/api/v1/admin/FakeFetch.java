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
package com.auto1.pantera.api.v1.admin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Canned in-process fetch for tests: {@code repo + path} to a response,
 * 404 otherwise. Records every request with its credentials.
 *
 * @since 2.2.9
 */
final class FakeFetch implements RepoFetch {

    /**
     * Canned responses.
     */
    private final Map<String, Fetched> answers = new ConcurrentHashMap<>();

    /**
     * Requests seen ({@code repo path auth}).
     */
    private final List<String> seen = new CopyOnWriteArrayList<>();

    /**
     * Side effect of every fetch.
     */
    private volatile java.util.function.Consumer<String> effect = target -> { };

    /**
     * Answer a request with a JSON/text body.
     *
     * @param repo Repository
     * @param path Raw path
     * @param status Status
     * @param body Body
     * @return This
     */
    FakeFetch answer(final String repo, final String path, final int status, final String body) {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        this.answers.put(
            repo + path,
            new Fetched(status, List.of(Map.entry("Content-Type", "application/json")),
                bytes, false, bytes.length)
        );
        return this;
    }

    /**
     * Answer a request with a status and the trace the serving path would
     * record for it.
     *
     * @param repo Repository
     * @param path Raw path
     * @param status Status
     * @param steps What each repository on the way answered
     * @return This
     */
    FakeFetch traced(
        final String repo, final String path, final int status,
        final com.auto1.pantera.http.log.RequestTrace.Step... steps
    ) {
        this.answers.put(
            repo + path,
            new Fetched(status, List.of(), new byte[0], false, 0, List.of(steps))
        );
        return this;
    }

    /**
     * Run something on every fetch, as the serving path has side effects.
     *
     * @param effect Side effect, given {@code repo + path} of the fetch
     * @return This
     */
    FakeFetch onFetch(final java.util.function.Consumer<String> effect) {
        this.effect = effect;
        return this;
    }

    /**
     * Requests seen.
     *
     * @return Requests
     */
    List<String> seen() {
        return this.seen;
    }

    @Override
    public CompletableFuture<Fetched> get(
        final String repo, final String path, final String authorization,
        final String accept, final int maxBody
    ) {
        this.seen.add(repo + " " + path + " " + authorization);
        this.effect.accept(repo + path);
        return CompletableFuture.completedFuture(this.answers.getOrDefault(
            repo + path, new Fetched(404, List.of(), new byte[0], false, 0)
        ));
    }
}
