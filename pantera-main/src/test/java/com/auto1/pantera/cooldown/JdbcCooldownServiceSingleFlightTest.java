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
package com.auto1.pantera.cooldown;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.db.ArtifactDbFactory;
import com.auto1.pantera.db.DbManager;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-P13 acceptance test for {@link JdbcCooldownService} —
 * N concurrent evaluate() calls for the same
 * {@code (repoType, repoName, artifact, version)} share one
 * downstream evaluation (one {@code inspector.releaseDate} call,
 * one round-trip through the cooldown cache + database).
 *
 * <p>Closes cooldown-redesign §5.1 — eliminates the thundering-herd
 * window between an L1 cache miss and the corresponding DB lookup.
 *
 * @since 2.2.0
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class JdbcCooldownServiceSingleFlightTest {

    @Container
    @SuppressWarnings("unused")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:15");

    private DataSource dataSource;

    private CooldownRepository repository;

    private JdbcCooldownService service;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        this.dataSource = new ArtifactDbFactory(this.settings(), "cooldowns").initialize();
        DbManager.migrate(this.dataSource);
        this.repository = new CooldownRepository(this.dataSource);
        this.executor = Executors.newFixedThreadPool(8);
        this.service = new JdbcCooldownService(
            CooldownSettings.defaults(),
            this.repository,
            this.executor
        );
    }

    @AfterEach
    void tearDown() {
        this.executor.shutdownNow();
        if (this.dataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }

    @Test
    void concurrentEvaluatesForSameKeyProduceOneInspectorCall() throws Exception {
        final int callers = 100;
        final CountingInspector inspector = new CountingInspector(
            Duration.ofMillis(200), Instant.now()
        );
        final CooldownRequest request = new CooldownRequest(
            "maven-proxy", "central",
            "com.example.coalesced", "1.0.0",
            "alice", Instant.now()
        );

        final CountDownLatch ready = new CountDownLatch(callers);
        final CountDownLatch fire = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(callers);
        @SuppressWarnings("unchecked")
        final CompletableFuture<CooldownResult>[] futures = new CompletableFuture[callers];
        try {
            for (int i = 0; i < callers; i++) {
                final int idx = i;
                futures[idx] = CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                    return this.service.evaluate(request, inspector).join();
                }, pool);
            }
            ready.await(5, TimeUnit.SECONDS);
            fire.countDown();
            CompletableFuture.allOf(futures).get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        MatcherAssert.assertThat(
            "inspector.releaseDate is invoked exactly once for "
            + callers + " concurrent evaluates of the same key",
            inspector.calls.get(),
            new IsEqual<>(1)
        );
    }

    @Test
    void distinctKeysAreNotCoalesced() throws Exception {
        final int distinct = 8;
        final CountingInspector inspector = new CountingInspector(
            Duration.ZERO, Instant.now()
        );
        final ExecutorService pool = Executors.newFixedThreadPool(distinct);
        @SuppressWarnings("unchecked")
        final CompletableFuture<CooldownResult>[] futures = new CompletableFuture[distinct];
        try {
            for (int i = 0; i < distinct; i++) {
                final String version = "1." + i + ".0";
                futures[i] = CompletableFuture.supplyAsync(() -> {
                    final CooldownRequest request = new CooldownRequest(
                        "maven-proxy", "central",
                        "com.example.distinct", version, "bob", Instant.now()
                    );
                    return this.service.evaluate(request, inspector).join();
                }, pool);
            }
            CompletableFuture.allOf(futures).get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        MatcherAssert.assertThat(
            "distinct keys produce one inspector call each — no coalescing across keys",
            inspector.calls.get(),
            new IsEqual<>(distinct)
        );
    }

    @Test
    void sequentialEvaluatesAfterCompletionReusesCache() {
        final CountingInspector inspector = new CountingInspector(
            Duration.ZERO, Instant.now()
        );
        final CooldownRequest request = new CooldownRequest(
            "maven-proxy", "central",
            "com.example.sequential", "2.0.0", "carol", Instant.now()
        );
        this.service.evaluate(request, inspector).join();
        this.service.evaluate(request, inspector).join();
        MatcherAssert.assertThat(
            "second sequential evaluate hits the 3-tier cache (no second inspector call)",
            inspector.calls.get(),
            new IsEqual<>(1)
        );
    }

    private YamlMapping settings() {
        return Yaml.createYamlMappingBuilder().add(
            "artifacts_database",
            Yaml.createYamlMappingBuilder()
                .add(ArtifactDbFactory.YAML_HOST, POSTGRES.getHost())
                .add(ArtifactDbFactory.YAML_PORT, String.valueOf(POSTGRES.getFirstMappedPort()))
                .add(ArtifactDbFactory.YAML_DATABASE, POSTGRES.getDatabaseName())
                .add(ArtifactDbFactory.YAML_USER, POSTGRES.getUsername())
                .add(ArtifactDbFactory.YAML_PASSWORD, POSTGRES.getPassword())
                .build()
        ).build();
    }

    /**
     * Counts {@link #releaseDate} invocations and optionally delays
     * each one so concurrent callers stack up on the in-flight future
     * inside the single-flight coalescer.
     */
    private static final class CountingInspector implements CooldownInspector {

        private final AtomicInteger calls = new AtomicInteger();

        private final Duration delay;

        private final Instant fixedRelease;

        CountingInspector(final Duration delay, final Instant fixedRelease) {
            this.delay = delay;
            this.fixedRelease = fixedRelease;
        }

        @Override
        public CompletableFuture<Optional<Instant>> releaseDate(
            final String artifact, final String version
        ) {
            this.calls.incrementAndGet();
            if (this.delay.isZero()) {
                return CompletableFuture.completedFuture(Optional.of(this.fixedRelease));
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(this.delay.toMillis());
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return Optional.of(this.fixedRelease);
            });
        }

        @Override
        public CompletableFuture<List<CooldownDependency>> dependencies(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
