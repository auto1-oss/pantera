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
        return CompletableFuture.completedFuture(this.answers.getOrDefault(
            repo + path, new Fetched(404, List.of(), new byte[0], false, 0)
        ));
    }
}
