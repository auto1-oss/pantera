/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.cache.ValkeyConnection;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.rq.RequestLine;
import com.artipie.scheduling.QuartzService;
import com.artipie.settings.Settings;

import javax.json.Json;
import javax.json.JsonObject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Multi-component health check slice.
 * <p>
 * Probes storage, database, Valkey, Quartz scheduler, and HTTP client,
 * returning JSON with per-component status and latency. HTTP status codes:
 * <ul>
 *   <li>200 -- all components healthy, or degraded (one non-storage component down)</li>
 *   <li>503 -- unhealthy (storage down, or more than one component down)</li>
 * </ul>
 *
 * @since 1.20.13
 */
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public final class HealthSlice implements Slice {

    /**
     * Storage to probe.
     */
    private final Storage storage;

    /**
     * Optional database data source.
     */
    private final Optional<DataSource> dataSource;

    /**
     * Optional Valkey ping probe. Returns a future with true if connected.
     */
    private final Optional<Supplier<CompletableFuture<Boolean>>> valkeyProbe;

    /**
     * Optional Quartz scheduler status probe. Returns true if running.
     */
    private final Optional<Supplier<Boolean>> quartzProbe;

    /**
     * Optional HTTP client status probe. Returns true if operational.
     */
    private final Optional<Supplier<Boolean>> httpClientProbe;

    /**
     * Backward-compatible constructor that extracts storage, database,
     * and Valkey connection from Artipie settings. Quartz and HTTP client
     * are not available through settings and default to not_configured.
     *
     * @param settings Artipie settings
     */
    public HealthSlice(final Settings settings) {
        this(
            settings.configStorage(),
            settings.artifactsDatabase(),
            settings.valkeyConnection().map(vc -> (Supplier<CompletableFuture<Boolean>>) vc::pingAsync),
            Optional.empty(),
            Optional.empty()
        );
    }

    /**
     * Legacy constructor with only storage and database.
     * Valkey, Quartz, and HTTP client default to not_configured.
     *
     * @param storage Storage to probe
     * @param dataSource Optional database data source
     */
    public HealthSlice(final Storage storage, final Optional<DataSource> dataSource) {
        this(storage, dataSource, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Full constructor with all five probe dependencies.
     *
     * @param storage Storage to probe
     * @param dataSource Optional database data source
     * @param valkeyProbe Optional Valkey ping supplier (returns future boolean)
     * @param quartzProbe Optional Quartz running-status supplier
     * @param httpClientProbe Optional HTTP client operational-status supplier
     */
    public HealthSlice(
        final Storage storage,
        final Optional<DataSource> dataSource,
        final Optional<Supplier<CompletableFuture<Boolean>>> valkeyProbe,
        final Optional<Supplier<Boolean>> quartzProbe,
        final Optional<Supplier<Boolean>> httpClientProbe
    ) {
        this.storage = storage;
        this.dataSource = dataSource;
        this.valkeyProbe = valkeyProbe;
        this.quartzProbe = quartzProbe;
        this.httpClientProbe = httpClientProbe;
    }

    /**
     * Factory method to create a HealthSlice with Valkey, Quartz, and HTTP client probes
     * from concrete service objects.
     *
     * @param storage Storage to probe
     * @param dataSource Optional database data source
     * @param valkeyConn Optional Valkey connection
     * @param quartz Optional Quartz scheduler service
     * @param httpClient Optional HTTP client operational status supplier
     * @return HealthSlice with all probes configured
     */
    public static HealthSlice withServices(
        final Storage storage,
        final Optional<DataSource> dataSource,
        final Optional<ValkeyConnection> valkeyConn,
        final Optional<QuartzService> quartz,
        final Optional<Supplier<Boolean>> httpClient
    ) {
        return new HealthSlice(
            storage,
            dataSource,
            valkeyConn.map(vc -> (Supplier<CompletableFuture<Boolean>>) vc::pingAsync),
            quartz.map(qs -> (Supplier<Boolean>) qs::isRunning),
            httpClient
        );
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final CompletableFuture<JsonObject> storageFuture = this.probeStorage();
        final CompletableFuture<JsonObject> dbFuture = this.probeDatabase();
        final CompletableFuture<JsonObject> valkeyFuture = this.probeValkey();
        final CompletableFuture<JsonObject> quartzFuture = this.probeQuartz();
        final CompletableFuture<JsonObject> httpFuture = this.probeHttpClient();
        return CompletableFuture.allOf(
            storageFuture, dbFuture, valkeyFuture, quartzFuture, httpFuture
        ).thenApply(ignored -> {
            final JsonObject storageResult = storageFuture.join();
            final JsonObject dbResult = dbFuture.join();
            final JsonObject valkeyResult = valkeyFuture.join();
            final JsonObject quartzResult = quartzFuture.join();
            final JsonObject httpResult = httpFuture.join();
            final String storageStatus = storageResult.getString("status");
            final int downCount = countDown(storageStatus)
                + countDown(dbResult.getString("status"))
                + countDown(valkeyResult.getString("status"))
                + countDown(quartzResult.getString("status"))
                + countDown(httpResult.getString("status"));
            final boolean storageDown = "unhealthy".equals(storageStatus);
            final String overallStatus;
            if (downCount == 0) {
                overallStatus = "healthy";
            } else if (storageDown || downCount > 1) {
                overallStatus = "unhealthy";
            } else {
                overallStatus = "degraded";
            }
            final JsonObject json = Json.createObjectBuilder()
                .add("status", overallStatus)
                .add("components", Json.createObjectBuilder()
                    .add("storage", storageResult)
                    .add("database", dbResult)
                    .add("valkey", valkeyResult)
                    .add("quartz", quartzResult)
                    .add("http_client", httpResult)
                    .build()
                ).build();
            if ("unhealthy".equals(overallStatus)) {
                return ResponseBuilder.unavailable()
                    .jsonBody(json)
                    .build();
            }
            return ResponseBuilder.ok()
                .jsonBody(json)
                .build();
        }).toCompletableFuture();
    }

    /**
     * Probes storage by listing root keys with a 5-second timeout.
     *
     * @return JSON object with status and latency_ms
     */
    private CompletableFuture<JsonObject> probeStorage() {
        final long start = System.nanoTime();
        try {
            return this.storage.list(Key.ROOT)
                .orTimeout(5, TimeUnit.SECONDS)
                .thenApply(keys -> {
                    final long latency = (System.nanoTime() - start) / 1_000_000;
                    return Json.createObjectBuilder()
                        .add("status", "ok")
                        .add("latency_ms", latency)
                        .build();
                })
                .exceptionally(ex -> {
                    final long latency = (System.nanoTime() - start) / 1_000_000;
                    EcsLogger.error("com.artipie.health")
                        .message("Health check storage probe failed")
                        .eventCategory("system")
                        .eventAction("health_check")
                        .eventOutcome("failure")
                        .error(ex)
                        .log();
                    return Json.createObjectBuilder()
                        .add("status", "unhealthy")
                        .add("latency_ms", latency)
                        .build();
                }).toCompletableFuture();
        } catch (final Exception ex) {
            final long latency = (System.nanoTime() - start) / 1_000_000;
            EcsLogger.error("com.artipie.health")
                .message("Health check storage probe threw exception")
                .eventCategory("system")
                .eventAction("health_check")
                .eventOutcome("failure")
                .error(ex)
                .log();
            return CompletableFuture.completedFuture(
                Json.createObjectBuilder()
                    .add("status", "unhealthy")
                    .add("latency_ms", latency)
                    .build()
            );
        }
    }

    /**
     * Probes database connectivity if a DataSource is configured.
     * Uses {@code connection.isValid(5)} with a 5-second timeout.
     *
     * @return JSON object with status and latency_ms
     */
    @SuppressWarnings("PMD.CloseResource")
    private CompletableFuture<JsonObject> probeDatabase() {
        if (this.dataSource.isEmpty()) {
            return CompletableFuture.completedFuture(
                Json.createObjectBuilder()
                    .add("status", "not_configured")
                    .build()
            );
        }
        return CompletableFuture.supplyAsync(() -> {
            final long start = System.nanoTime();
            try (Connection conn = this.dataSource.get().getConnection()) {
                final boolean valid = conn.isValid(5);
                final long latency = (System.nanoTime() - start) / 1_000_000;
                if (valid) {
                    return Json.createObjectBuilder()
                        .add("status", "ok")
                        .add("latency_ms", latency)
                        .build();
                }
                return Json.createObjectBuilder()
                    .add("status", "unhealthy")
                    .add("latency_ms", latency)
                    .build();
            } catch (final Exception ex) {
                final long latency = (System.nanoTime() - start) / 1_000_000;
                EcsLogger.error("com.artipie.health")
                    .message("Health check database probe failed")
                    .eventCategory("system")
                    .eventAction("health_check")
                    .eventOutcome("failure")
                    .error(ex)
                    .log();
                return Json.createObjectBuilder()
                    .add("status", "unhealthy")
                    .add("latency_ms", latency)
                    .build();
            }
        });
    }

    /**
     * Probes Valkey connectivity using the configured ping supplier.
     * Returns "not_configured" if Valkey is not enabled.
     *
     * @return JSON object with status and latency_ms
     */
    private CompletableFuture<JsonObject> probeValkey() {
        if (this.valkeyProbe.isEmpty()) {
            return CompletableFuture.completedFuture(
                Json.createObjectBuilder()
                    .add("status", "not_configured")
                    .build()
            );
        }
        final long start = System.nanoTime();
        try {
            return this.valkeyProbe.get().get()
                .orTimeout(5, TimeUnit.SECONDS)
                .thenApply(pong -> {
                    final long latency = (System.nanoTime() - start) / 1_000_000;
                    final String status;
                    if (pong) {
                        status = "ok";
                    } else {
                        status = "unhealthy";
                    }
                    return Json.createObjectBuilder()
                        .add("status", status)
                        .add("latency_ms", latency)
                        .build();
                })
                .exceptionally(ex -> {
                    final long latency = (System.nanoTime() - start) / 1_000_000;
                    EcsLogger.error("com.artipie.health")
                        .message("Health check Valkey probe failed")
                        .eventCategory("system")
                        .eventAction("health_check")
                        .eventOutcome("failure")
                        .error(ex)
                        .log();
                    return Json.createObjectBuilder()
                        .add("status", "unhealthy")
                        .add("latency_ms", latency)
                        .build();
                });
        } catch (final Exception ex) {
            final long latency = (System.nanoTime() - start) / 1_000_000;
            EcsLogger.error("com.artipie.health")
                .message("Health check Valkey probe threw exception")
                .eventCategory("system")
                .eventAction("health_check")
                .eventOutcome("failure")
                .error(ex)
                .log();
            return CompletableFuture.completedFuture(
                Json.createObjectBuilder()
                    .add("status", "unhealthy")
                    .add("latency_ms", latency)
                    .build()
            );
        }
    }

    /**
     * Probes Quartz scheduler status using the configured status supplier.
     * Returns "not_configured" if Quartz is not available.
     *
     * @return JSON object with status and latency_ms
     */
    private CompletableFuture<JsonObject> probeQuartz() {
        if (this.quartzProbe.isEmpty()) {
            return CompletableFuture.completedFuture(
                Json.createObjectBuilder()
                    .add("status", "not_configured")
                    .build()
            );
        }
        return CompletableFuture.supplyAsync(() -> {
            final long start = System.nanoTime();
            try {
                final boolean running = this.quartzProbe.get().get();
                final long latency = (System.nanoTime() - start) / 1_000_000;
                final String status;
                if (running) {
                    status = "ok";
                } else {
                    status = "unhealthy";
                }
                return Json.createObjectBuilder()
                    .add("status", status)
                    .add("latency_ms", latency)
                    .build();
            } catch (final Exception ex) {
                final long latency = (System.nanoTime() - start) / 1_000_000;
                EcsLogger.error("com.artipie.health")
                    .message("Health check Quartz probe failed")
                    .eventCategory("system")
                    .eventAction("health_check")
                    .eventOutcome("failure")
                    .error(ex)
                    .log();
                return Json.createObjectBuilder()
                    .add("status", "unhealthy")
                    .add("latency_ms", latency)
                    .build();
            }
        });
    }

    /**
     * Probes HTTP client operational status using the configured status supplier.
     * Returns "not_configured" if HTTP client status supplier is not provided.
     *
     * @return JSON object with status and latency_ms
     */
    private CompletableFuture<JsonObject> probeHttpClient() {
        if (this.httpClientProbe.isEmpty()) {
            return CompletableFuture.completedFuture(
                Json.createObjectBuilder()
                    .add("status", "not_configured")
                    .build()
            );
        }
        final long start = System.nanoTime();
        try {
            final boolean operational = this.httpClientProbe.get().get();
            final long latency = (System.nanoTime() - start) / 1_000_000;
            final String status;
            if (operational) {
                status = "ok";
            } else {
                status = "unhealthy";
            }
            return CompletableFuture.completedFuture(
                Json.createObjectBuilder()
                    .add("status", status)
                    .add("latency_ms", latency)
                    .build()
            );
        } catch (final Exception ex) {
            final long latency = (System.nanoTime() - start) / 1_000_000;
            EcsLogger.error("com.artipie.health")
                .message("Health check HTTP client probe failed")
                .eventCategory("system")
                .eventAction("health_check")
                .eventOutcome("failure")
                .error(ex)
                .log();
            return CompletableFuture.completedFuture(
                Json.createObjectBuilder()
                    .add("status", "unhealthy")
                    .add("latency_ms", latency)
                    .build()
            );
        }
    }

    /**
     * Counts whether a component status is "down" (unhealthy).
     * "not_configured" is not counted as down.
     *
     * @param status Component status string
     * @return 1 if down, 0 otherwise
     */
    private static int countDown(final String status) {
        return "unhealthy".equals(status) ? 1 : 0;
    }
}
