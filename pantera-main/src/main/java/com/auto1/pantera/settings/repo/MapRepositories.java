/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.settings.repo;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.trace.TraceContextExecutor;
import com.auto1.pantera.settings.AliasSettings;
import com.auto1.pantera.settings.ConfigFile;
import com.auto1.pantera.settings.Settings;
import com.auto1.pantera.settings.StorageByAlias;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class MapRepositories implements Repositories, AutoCloseable {

    private static final Duration DEFAULT_WATCH_INTERVAL = Duration.ofSeconds(2);

    private final Settings settings;

    private final AtomicReference<RepoSnapshot> snapshot;

    private final ExecutorService loader;

    private final AtomicReference<CompletableFuture<RepoSnapshot>> reloading;

    private final RepoConfigWatcher watcher;

    private final AtomicBoolean closed;

    private final AtomicLong version;

    public MapRepositories(final Settings settings) {
        this(settings, DEFAULT_WATCH_INTERVAL);
    }

    MapRepositories(final Settings settings, final Duration watchInterval) {
        this(settings, watchInterval, createLoader());
    }

    MapRepositories(final Settings settings, final Duration watchInterval,
        final ExecutorService loader) {
        this(settings, watchInterval, loader, null);
    }

    MapRepositories(final Settings settings, final Duration watchInterval,
        final ExecutorService loader, final RepoConfigWatcher customWatcher) {
        this(settings, watchInterval, loader, customWatcher, false);
    }

    /**
     * Full constructor with async option.
     *
     * @param settings Application settings.
     * @param watchInterval Watch interval for config changes.
     * @param loader Executor for config loading.
     * @param customWatcher Custom watcher or null.
     * @param asyncStartup If true, don't wait for initial load (faster startup but may 404 briefly).
     */
    MapRepositories(final Settings settings, final Duration watchInterval,
        final ExecutorService loader, final RepoConfigWatcher customWatcher,
        final boolean asyncStartup) {
        this.settings = settings;
        this.snapshot = new AtomicReference<>(RepoSnapshot.empty());
        this.loader = loader;
        this.reloading = new AtomicReference<>();
        this.closed = new AtomicBoolean(false);
        this.version = new AtomicLong();
        this.watcher = customWatcher == null
            ? createWatcher(settings.repoConfigsStorage(), watchInterval)
            : customWatcher;
        // Schedule initial load - wait for it unless async startup is requested
        final CompletableFuture<RepoSnapshot> initial = this.scheduleRefresh();
        if (!asyncStartup) {
            // Default: blocking startup for backward compatibility and test reliability
            initial.join();
        } else {
            // ENTERPRISE: Non-blocking startup - server accepts requests while configs load.
            // Empty snapshot serves 404 until first load completes (typically <1s).
            EcsLogger.info("com.auto1.pantera.settings")
                .message("Repository loading started asynchronously (non-blocking startup)")
                .eventCategory("configuration")
                .eventAction("startup")
                .log();
        }
        this.watcher.start();
    }

    @Override
    public Optional<RepoConfig> config(final String name) {
        final String normalized = new ConfigFile(name).name();
        return Optional.ofNullable(this.snapshot.get().configs().get(normalized));
    }

    @Override
    public Collection<RepoConfig> configs() {
        return this.snapshot.get().configs().values();
    }

    @Override
    public CompletableFuture<Void> refreshAsync() {
        return this.scheduleRefresh().thenApply(ignored -> null);
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.watcher.close();
            this.loader.shutdownNow();
        }
    }

    private CompletableFuture<RepoSnapshot> scheduleRefresh() {
        if (this.closed.get()) {
            final CompletableFuture<RepoSnapshot> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("Repositories closed"));
            return failed;
        }
        while (true) {
            final CompletableFuture<RepoSnapshot> current = this.reloading.get();
            if (current != null && !current.isDone()) {
                return current;
            }
            final CompletableFuture<RepoSnapshot> created = CompletableFuture
                .supplyAsync(this::loadSnapshot, this.loader)
                .thenApply(this::applySnapshot);
            created.whenComplete(
                (snap, err) -> {
                    this.reloading.compareAndSet(created, null);
                    if (err != null) {
                        EcsLogger.error("com.auto1.pantera.settings")
                            .message("Failed to refresh repository configurations")
                            .eventCategory("configuration")
                            .eventAction("config_load")
                            .eventOutcome("failure")
                            .error(err)
                            .log();
                    }
                }
            );
            if (this.reloading.compareAndSet(current, created)) {
                return created;
            }
        }
    }

    private RepoSnapshot applySnapshot(final RepoSnapshot snap) {
        this.snapshot.set(snap);
        EcsLogger.info("com.auto1.pantera.settings")
            .message(
                String.format(
                    "Loaded %d repository configurations (version %d)",
                    snap.configs().size(),
                    snap.version()
                )
            )
            .eventCategory("configuration")
            .eventAction("config_load")
            .eventOutcome("success")
            .log();
        return snap;
    }

    private RepoSnapshot loadSnapshot() {
        final long start = System.nanoTime();
        final Storage storage = this.settings.repoConfigsStorage();
        final Collection<Key> keys = storage.list(Key.ROOT).join();
        final List<CompletableFuture<RepoConfig>> futures = keys.stream()
            .map(this::loadRepoConfigAsync)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        final Map<String, RepoConfig> loaded = futures.stream()
            .map(CompletableFuture::join)
            .filter(Objects::nonNull)
            .collect(
                Collectors.toMap(
                    RepoConfig::name,
                    config -> config,
                    (first, second) -> second
                )
            );
        final RepoSnapshot snap = new RepoSnapshot(
            Collections.unmodifiableMap(loaded),
            Instant.now(),
            this.version.incrementAndGet()
        );
        final long duration = System.nanoTime() - start;
        EcsLogger.debug("com.auto1.pantera.settings")
            .message(
                String.format(
                    "Repository snapshot v%d built in %d ms",
                    snap.version(),
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(duration)
                )
            )
            .eventCategory("configuration")
            .eventAction("config_load")
            .eventOutcome("success")
            .log();
        return snap;
    }

    private CompletableFuture<RepoConfig> loadRepoConfigAsync(final Key key) {
        final ConfigFile file = new ConfigFile(key);
        if (!file.isSystem() && file.isYamlOrYml()) {
            final Storage storage = this.settings.repoConfigsStorage();
            final CompletableFuture<StorageByAlias> alias = new AliasSettings(storage).find(key);
            final CompletableFuture<String> content = file.valueFrom(storage)
                .thenCompose(cnt -> cnt.asStringFuture())
                .toCompletableFuture();
            return alias.thenCombine(
                content,
                (aliases, yaml) -> {
                    try {
                        return RepoConfig.from(
                            Yaml.createYamlInput(yaml).readYamlMapping(),
                            aliases,
                            new Key.From(file.name()),
                            this.settings.caches().storagesCache(),
                            this.settings.metrics().storage()
                        );
                    } catch (final Exception err) {
                        EcsLogger.error("com.auto1.pantera.settings")
                            .message("Cannot parse repository config file")
                            .eventCategory("configuration")
                            .eventAction("config_parse")
                            .eventOutcome("failure")
                            .field("file.path", file.name())
                            .error(err)
                            .log();
                        return null;
                    }
                }
            ).exceptionally(err -> {
                EcsLogger.error("com.auto1.pantera.settings")
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

    private static ExecutorService createLoader() {
        return TraceContextExecutor.wrap(
            Executors.newSingleThreadExecutor(
                runnable -> {
                    final Thread thread = new Thread(runnable, "artipie.repo.loader");
                    thread.setDaemon(true);
                    return thread;
                }
            )
        );
    }

    private RepoConfigWatcher createWatcher(final Storage storage, final Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return RepoConfigWatcher.disabled();
        }
        return new RepoConfigWatcher(storage, interval, this::scheduleRefresh);
    }

    private static final class RepoSnapshot {
        private static final RepoSnapshot EMPTY = new RepoSnapshot(Collections.emptyMap(), Instant.EPOCH, 0);
        private final Map<String, RepoConfig> configs;
        private final Instant updated;
        private final long version;
        RepoSnapshot(final Map<String, RepoConfig> configs, final Instant updated, final long version) {
            this.configs = configs;
            this.updated = updated;
            this.version = version;
        }
        static RepoSnapshot empty() {
            return EMPTY;
        }
        Map<String, RepoConfig> configs() {
            return this.configs;
        }
        long version() {
            return this.version;
        }
        Instant updated() {
            return this.updated;
        }
    }
}
