/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.settings.repo;

import com.amihaiemil.eoyaml.Yaml;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.settings.AliasSettings;
import com.artipie.settings.ConfigFile;
import com.artipie.settings.Settings;
import com.artipie.settings.StorageByAlias;
import com.artipie.http.log.EcsLogger;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class MapRepositories implements Repositories {

    private final Settings settings;

    private final Map<String, RepoConfig> map;

    public MapRepositories(final Settings settings) {
        this.settings = settings;
        this.map = new ConcurrentHashMap<>();
        // Initial synchronous refresh for backward compatibility
        refresh();
    }

    @Override
    public Optional<RepoConfig> config(final String name) {
        return Optional.ofNullable(this.map.get(new ConfigFile(name).name()));
    }

    @Override
    public Collection<RepoConfig> configs() {
        return Collections.unmodifiableCollection(this.map.values());
    }

    @Override
    public void refresh() {
        // Synchronous wrapper for backward compatibility
        refreshAsync().join();
    }

    /**
     * Refresh repository configurations asynchronously.
     * Loads all repositories in parallel for fast startup.
     *
     * @return CompletableFuture that completes when all repos are loaded
     */
    public CompletableFuture<Void> refreshAsync() {
        this.map.clear();

        return settings.repoConfigsStorage()
            .list(Key.ROOT)
            .thenCompose(keys -> {
                // Load all repositories in parallel
                List<CompletableFuture<RepoConfig>> futures = keys.stream()
                    .map(key -> loadRepoConfigAsync(key))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

                if (futures.isEmpty()) {
                    return CompletableFuture.completedFuture(null);
                }

                // Wait for all to complete - use thenCompose to avoid blocking
                return CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
                ).thenCompose(v -> {
                    // Collect results non-blockingly using thenApply on each future
                    List<CompletableFuture<Void>> updates = futures.stream()
                        .map(f -> f.thenAccept(config -> {
                            if (config != null) {
                                this.map.put(config.name(), config);
                            }
                        }).exceptionally(e -> {
                            EcsLogger.error("com.artipie.settings")
                                .message("Failed to load repository config")
                                .eventCategory("configuration")
                                .eventAction("config_load")
                                .eventOutcome("failure")
                                .error(e)
                                .log();
                            return null;
                        }))
                        .collect(Collectors.toList());

                    return CompletableFuture.allOf(
                        updates.toArray(new CompletableFuture[0])
                    ).thenApply(ignored -> {
                        EcsLogger.info("com.artipie.settings")
                            .message("Loaded " + this.map.size() + " repository configurations")
                            .eventCategory("configuration")
                            .eventAction("config_load")
                            .eventOutcome("success")
                            .log();
                        return null;
                    });
                });
            });
    }

    /**
     * Load a single repository configuration asynchronously.
     *
     * @param key Repository config file key
     * @return CompletableFuture with RepoConfig or null if not applicable
     */
    private CompletableFuture<RepoConfig> loadRepoConfigAsync(final Key key) {
        final ConfigFile file = new ConfigFile(key);

        if (!file.isSystem() && file.isYamlOrYml()) {
            final Storage storage = this.settings.repoConfigsStorage();

            // Load alias and content in parallel
            CompletableFuture<StorageByAlias> aliasFuture =
                new AliasSettings(storage).find(key);

            // Use asStringFuture() instead of asString() to avoid blocking
            CompletableFuture<String> contentFuture =
                file.valueFrom(storage)
                    .thenCompose(content -> content.asStringFuture())
                    .toCompletableFuture();

            // Combine results
            return aliasFuture.thenCombine(
                contentFuture,
                (alias, content) -> {
                    try {
                        return RepoConfig.from(
                            Yaml.createYamlInput(content).readYamlMapping(),
                            alias,
                            new Key.From(file.name()),
                            this.settings.caches().storagesCache(),
                            this.settings.metrics().storage()
                        );
                    } catch (Exception e) {
                        EcsLogger.error("com.artipie.settings")
                            .message("Cannot parse repository config file")
                            .eventCategory("configuration")
                            .eventAction("config_parse")
                            .eventOutcome("failure")
                            .field("file.path", file.name())
                            .error(e)
                            .log();
                        return null;
                    }
                }
            ).exceptionally(err -> {
                EcsLogger.error("com.artipie.settings")
                    .message("Failed to load repository config")
                    .eventCategory("configuration")
                    .eventAction("config_load")
                    .eventOutcome("failure")
                    .field("file.path", file.name())
                    .error(err)
                    .log();
                return null;
            });
        }

        return null;
    }
}
