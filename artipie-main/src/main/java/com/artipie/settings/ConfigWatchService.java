/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.settings;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service to watch artipie.yml for changes and hot reload global_prefixes.
 * Implements 500ms debounce to avoid excessive reloads.
 *
 * @since 1.0
 */
public final class ConfigWatchService implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigWatchService.class);

    /**
     * Debounce delay in milliseconds.
     */
    private static final long DEBOUNCE_MS = 500L;

    /**
     * Path to artipie.yml config file.
     */
    private final Path configPath;

    /**
     * Prefixes configuration to update.
     */
    private final PrefixesConfig prefixesConfig;

    /**
     * Watch service for file system events.
     */
    private final WatchService watcher;

    /**
     * Executor for debounced reload.
     */
    private final ScheduledExecutorService executor;

    /**
     * Timestamp of last reload trigger.
     */
    private final AtomicLong lastTrigger;

    /**
     * Flag indicating if service is running.
     */
    private final AtomicBoolean running;

    /**
     * Watch thread.
     */
    private Thread watchThread;

    /**
     * Constructor.
     *
     * @param configPath Path to artipie.yml
     * @param prefixesConfig Prefixes configuration to update
     * @throws IOException If watch service cannot be created
     */
    public ConfigWatchService(
        final Path configPath,
        final PrefixesConfig prefixesConfig
    ) throws IOException {
        this.configPath = configPath;
        this.prefixesConfig = prefixesConfig;
        this.watcher = FileSystems.getDefault().newWatchService();
        this.executor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                final Thread thread = new Thread(r, "config-reload");
                thread.setDaemon(true);
                return thread;
            }
        );
        this.lastTrigger = new AtomicLong(0);
        this.running = new AtomicBoolean(false);
    }

    /**
     * Start watching for config file changes.
     */
    public void start() {
        if (this.running.compareAndSet(false, true)) {
            try {
                // Watch the directory containing the config file
                this.configPath.getParent().register(
                    this.watcher,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE
                );
                LOGGER.info("Started watching config file: {}", this.configPath);

                this.watchThread = new Thread(this::watchLoop, "config-watcher");
                this.watchThread.setDaemon(true);
                this.watchThread.start();
            } catch (final IOException ex) {
                LOGGER.error("Failed to start config watch service", ex);
                this.running.set(false);
            }
        }
    }

    /**
     * Main watch loop.
     */
    private void watchLoop() {
        while (this.running.get()) {
            try {
                final WatchKey key = this.watcher.take();
                for (final WatchEvent<?> event : key.pollEvents()) {
                    final WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    final WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    final Path filename = ev.context();
                    final Path changed = this.configPath.getParent().resolve(filename);

                    if (changed.equals(this.configPath)) {
                        this.triggerReload();
                    }
                }
                key.reset();
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (final ClosedWatchServiceException ex) {
                break;
            } catch (final Exception ex) {
                LOGGER.error("Error in config watch loop", ex);
            }
        }
    }

    /**
     * Trigger a debounced reload.
     */
    private void triggerReload() {
        final long now = System.currentTimeMillis();
        this.lastTrigger.set(now);

        this.executor.schedule(() -> {
            // Only reload if no newer trigger has occurred
            if (this.lastTrigger.get() == now) {
                this.reload();
            }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Reload prefixes from config file.
     */
    private void reload() {
        try {
            final List<String> newPrefixes = this.readPrefixes();
            this.prefixesConfig.update(newPrefixes);
            LOGGER.info(
                "Reloaded global_prefixes from config: {} (version: {})",
                newPrefixes,
                this.prefixesConfig.version()
            );
        } catch (final Exception ex) {
            LOGGER.error("Failed to reload config file: {}", this.configPath, ex);
        }
    }

    /**
     * Read prefixes from config file.
     *
     * @return List of prefixes
     * @throws IOException If file cannot be read
     */
    private List<String> readPrefixes() throws IOException {
        final YamlMapping yaml = Yaml.createYamlInput(
            this.configPath.toFile()
        ).readYamlMapping();

        final YamlMapping meta = yaml.yamlMapping("meta");
        if (meta == null) {
            return Collections.emptyList();
        }

        final YamlSequence seq = meta.yamlSequence("global_prefixes");
        if (seq == null || seq.isEmpty()) {
            return Collections.emptyList();
        }

        final List<String> result = new ArrayList<>(seq.size());
        seq.values().forEach(node -> {
            final String value = node.asScalar().value();
            if (value != null && !value.isBlank()) {
                result.add(value);
            }
        });

        return result;
    }

    @Override
    public void close() {
        if (this.running.compareAndSet(true, false)) {
            try {
                this.watcher.close();
            } catch (final IOException ex) {
                LOGGER.error("Error closing watch service", ex);
            }
            this.executor.shutdown();
            try {
                if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    this.executor.shutdownNow();
                }
            } catch (final InterruptedException ex) {
                this.executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            if (this.watchThread != null) {
                this.watchThread.interrupt();
            }
            LOGGER.info("Config watch service stopped");
        }
    }
}
