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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Rewrites a local Go module's {@code @v/list} after a management-API delete
 * removed version files from storage, so the list names exactly the versions
 * whose {@code .zip} is still stored (in Go semver order); a list left with
 * no version is removed.
 *
 * <p>The upload path only ever adds versions to the list, so a deleted
 * version stayed listed and {@code go get} failed downloading it. The
 * rewrite runs in the same per-list queue as the upload's rewrite.</p>
 *
 * @since 2.2.9
 */
public final class GoListPruner {

    /**
     * Version directory segment.
     */
    private static final String VERSIONS = "/@v";

    /**
     * Repository storage (repository-relative keys).
     */
    private final Storage storage;

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * Ctor.
     * @param storage Repository storage
     * @param repo Repository name
     */
    public GoListPruner(final Storage storage, final String repo) {
        this.storage = storage;
        this.repo = repo;
    }

    /**
     * Rewrite the list of the module a deleted path belonged to.
     * @param deleted Deleted storage path, repository-relative
     * @return Completion
     */
    public CompletableFuture<Void> afterDelete(final String deleted) {
        String clean = deleted.trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        final int idx = clean.lastIndexOf(GoListPruner.VERSIONS + '/');
        final String module;
        if (idx > 0) {
            module = clean.substring(0, idx);
        } else if (clean.endsWith(GoListPruner.VERSIONS)
            && clean.length() > GoListPruner.VERSIONS.length()) {
            module = clean.substring(0, clean.length() - GoListPruner.VERSIONS.length());
        } else {
            module = null;
        }
        final CompletableFuture<Void> res;
        if (module == null) {
            res = CompletableFuture.completedFuture(null);
        } else {
            final Key list = new Key.From(String.format("%s/@v/list", module));
            res = GoUploadSlice.SERIAL.run(
                this.repo + '|' + list.string(),
                () -> this.storage.exists(list).thenCompose(
                    exists -> {
                        if (!exists) {
                            return CompletableFuture.<Void>completedFuture(null);
                        }
                        return this.storage.list(
                            new Key.From(String.format("%s/@v", module))
                        ).thenCompose(keys -> this.rewrite(list, keys.stream()
                            .map(Key::string).collect(Collectors.toList())));
                    }
                )
            );
        }
        return res;
    }

    /**
     * Write the list of the stored versions, or remove it when none is left.
     * @param list List key
     * @param keys Keys under the module's {@code @v} directory
     * @return Completion
     */
    private CompletableFuture<Void> rewrite(final Key list, final List<String> keys) {
        final String prefix = list.parent().map(Key::string).orElse("") + '/';
        final List<String> versions = keys.stream()
            .filter(name -> name.startsWith(prefix) && name.endsWith(".zip"))
            .map(name -> name.substring(prefix.length(), name.length() - ".zip".length()))
            .filter(name -> name.startsWith("v") && name.indexOf('/') < 0)
            .distinct()
            .sorted(new GoVersionOrder())
            .collect(Collectors.toList());
        final CompletableFuture<Void> res;
        if (versions.isEmpty()) {
            res = this.storage.delete(list);
        } else {
            res = this.storage.save(
                list,
                new Content.From(
                    (String.join("\n", versions) + '\n').getBytes(StandardCharsets.UTF_8)
                )
            );
        }
        return res;
    }
}
