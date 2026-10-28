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
package com.auto1.pantera.scheduling;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.repo.RepoConfigYaml;
import com.auto1.pantera.test.TestStoragesCache;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link MetadataEventQueues}.
 * <p>
 * Prior to the WS2.2b fix, {@code proxyEventQueues} scheduled each proxy
 * repository's package-processor through the cluster-shared Quartz job
 * store (RAM or JDBC), so these tests asserted on {@code QuartzService}
 * job/trigger state. As of the fix, the processor runs on a per-node
 * {@link LocalEventDrainScheduler} instead — these tests now assert on
 * observable drain behaviour (invocation counts via queue draining), per
 * the project's no-wall-clock testing doctrine, rather than on Quartz
 * internals that no longer apply to this pipeline.
 */
class MetadataEventQueuesTest {

    @Test
    void createsQueueAndReturnsSameQueueOnSecondCall(@TempDir final Path temp) {
        final RepoConfig cfg = RepoConfig.from(
            new RepoConfigYaml("npm-proxy").withFileStorage(temp).yaml(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From("my-npm-proxy"),
            new TestStoragesCache(), false
        );
        final MetadataEventQueues events = new MetadataEventQueues(new LinkedList<>());
        final Optional<Queue<ProxyArtifactEvent>> first = events.proxyEventQueues(cfg);
        MatcherAssert.assertThat(
            "Proxy queue should be present",
            first.isPresent(), new IsEqual<>(true)
        );
        final Optional<Queue<ProxyArtifactEvent>> second = events.proxyEventQueues(cfg);
        MatcherAssert.assertThat(
            "After second call the same queue is returned",
            first.get(), new IsEqual<>(second.orElseThrow())
        );
        events.stopProxyMetadataProcessing(cfg.name());
    }

    @Test
    @Timeout(15)
    void proxyEventsAreDrainedOnANodeLocalScheduleNotClusteredQuartz(
        @TempDir final Path temp
    ) {
        final RepoConfig cfg = RepoConfig.from(
            new RepoConfigYaml("maven-proxy").withFileStorage(temp).withSettings(
                Yaml.createYamlMappingBuilder().add("threads_count", "2")
                    .add("interval_seconds", "1").build()
            ).yaml(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From("my-maven-proxy"),
            new TestStoragesCache(), false
        );
        final MetadataEventQueues events = new MetadataEventQueues(new LinkedList<>());
        final Queue<ProxyArtifactEvent> queue = events.proxyEventQueues(cfg).orElseThrow();
        queue.add(new ProxyArtifactEvent(new Key.From("some/artifact/1.0.0"), cfg.name()));
        // The per-node scheduler drains this repository's queue on its own
        // dedicated executor (see LocalEventDrainScheduler) — proving the
        // queue empties out demonstrates the tick actually ran, without
        // depending on Quartz (RAM or JDBC) at all: this repository's
        // processor is never registered with any Quartz scheduler now.
        Awaitility.await().atMost(10, TimeUnit.SECONDS)
            .until(queue::isEmpty);
        events.stopProxyMetadataProcessing(cfg.name());
    }

    @Test
    @Timeout(15)
    void stoppingProxyMetadataProcessingHaltsFurtherDrains(@TempDir final Path temp) {
        // maven-proxy (not pypi-proxy): PyProxyPackageProcessor deliberately
        // re-queues an event forever while its artifact is missing from
        // storage (see PyProxyPackageProcessor#processPackageAsync), which
        // would race with this test's stop-then-assert-still-queued check.
        // MavenProxyPackageProcessor drops a missing-artifact event instead
        // of re-queuing it, so "drained" here unambiguously means "gone".
        final RepoConfig cfg = RepoConfig.from(
            new RepoConfigYaml("maven-proxy").withFileStorage(temp).withSettings(
                Yaml.createYamlMappingBuilder().add("threads_count", "1")
                    .add("interval_seconds", "1").build()
            ).yaml(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From("my-maven-proxy-2"),
            new TestStoragesCache(), false
        );
        final MetadataEventQueues events = new MetadataEventQueues(new LinkedList<>());
        final Queue<ProxyArtifactEvent> queue = events.proxyEventQueues(cfg).orElseThrow();
        queue.add(new ProxyArtifactEvent(new Key.From("warmup/1.0.0"), cfg.name()));
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(queue::isEmpty);
        events.stopProxyMetadataProcessing(cfg.name());
        queue.add(new ProxyArtifactEvent(new Key.From("after-stop/1.0.0"), cfg.name()));
        // Regression guard, not a latency assertion: the drain interval is
        // 1s, so a scheduler that wasn't actually stopped would drain this
        // within that window. pollDelay gives that window a chance to fire
        // before asserting the item is still there.
        Awaitility.await()
            .pollDelay(2, TimeUnit.SECONDS)
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> queue.size() == 1);
        MatcherAssert.assertThat(
            "A stopped proxy scheduler must not keep draining items added afterward",
            queue.size(), new IsEqual<>(1)
        );
    }
}
