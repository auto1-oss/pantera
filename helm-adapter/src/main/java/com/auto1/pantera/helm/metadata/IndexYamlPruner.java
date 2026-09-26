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
package com.auto1.pantera.helm.metadata;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.lock.storage.IndexUpdateLock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Removes the chart versions whose archive is no longer in storage from a
 * local Helm repository's {@code index.yaml} after a management-API delete.
 *
 * <p>{@code index.yaml} is written on push and served as stored, so a chart
 * archive deleted straight from storage stayed listed and {@code helm
 * install} failed downloading it. A version is kept while an archive named
 * {@code <chart>-<version>.tgz} is stored; a chart left with no version is
 * removed. The kept entries are written back unchanged (their
 * {@code created} time included). The rewrite holds the index lock the push
 * takes.</p>
 *
 * @since 2.2.9
 */
public final class IndexYamlPruner {

    /**
     * Repository storage (repository-relative keys).
     */
    private final Storage storage;

    /**
     * Ctor.
     * @param storage Repository storage
     */
    public IndexYamlPruner(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Prune the index after a delete.
     * @param deleted Deleted storage path, repository-relative
     * @return Names of the charts whose versions were removed
     */
    public CompletableFuture<Set<String>> afterDelete(final String deleted) {
        String clean = deleted.trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        final CompletableFuture<Set<String>> res;
        if (IndexYaml.INDEX_YAML.string().equals(clean)) {
            res = CompletableFuture.completedFuture(Set.of());
        } else {
            res = new IndexUpdateLock(this.storage, IndexYaml.INDEX_YAML).run(
                locked -> locked.exists(IndexYaml.INDEX_YAML).thenCompose(
                    exists -> {
                        if (!exists) {
                            return CompletableFuture.completedFuture(Set.<String>of());
                        }
                        return locked.list(Key.ROOT).thenCompose(
                            keys -> locked.value(IndexYaml.INDEX_YAML)
                                .thenCompose(Content::asStringFuture)
                                .thenCompose(
                                    yaml -> this.rewrite(
                                        yaml,
                                        keys.stream()
                                            .map(Key::string)
                                            .filter(key -> key.endsWith(".tgz"))
                                            .map(key -> key.substring(key.lastIndexOf('/') + 1))
                                            .collect(Collectors.toSet())
                                    )
                                )
                        );
                    }
                )
            );
        }
        return res;
    }

    /**
     * Rewrite the index without the versions whose archive is gone.
     * @param yaml Stored index
     * @param archives File names of the stored archives
     * @return Names of the charts whose versions were removed
     */
    private CompletableFuture<Set<String>> rewrite(final String yaml, final Set<String> archives) {
        final IndexYamlMapping index = new IndexYamlMapping(yaml);
        final Map<String, Object> entries = index.entries();
        final Set<String> changed = new HashSet<>();
        for (final String chart : new ArrayList<>(entries.keySet())) {
            final List<Map<String, Object>> versions = index.byChart(chart);
            final List<Map<String, Object>> kept = versions.stream()
                .filter(
                    entry -> archives.contains(
                        String.format("%s-%s.tgz", chart, String.valueOf(entry.get("version")))
                    )
                )
                .collect(Collectors.toList());
            if (kept.size() != versions.size()) {
                changed.add(chart);
                if (kept.isEmpty()) {
                    entries.remove(chart);
                } else {
                    entries.put(chart, kept);
                }
            }
        }
        final CompletableFuture<Set<String>> res;
        if (changed.isEmpty()) {
            res = CompletableFuture.completedFuture(Set.of());
        } else {
            res = this.storage.save(
                IndexYaml.INDEX_YAML,
                new Content.From(index.toString().getBytes(StandardCharsets.UTF_8))
            ).thenApply(nothing -> changed);
        }
        return res;
    }
}
