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
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;

/**
 * Test for {@link MetadataEventQueues}.
 */
class MetadataEventQueuesTest {

    /**
     * Quartz service.
     */
    private QuartzService service;

    @BeforeEach
    void init() {
        this.service = new QuartzService();
        this.service.start();
    }

    @AfterEach
    void stop() {
        this.service.stop();
    }

    @Test
    void createsQueueAndAddsJob() throws SchedulerException, InterruptedException {
        final RepoConfig cfg = RepoConfig.from(
            new RepoConfigYaml("npm-proxy").withFileStorage(Path.of("a/b/c")).yaml(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From("my-npm-proxy"),
            new TestStoragesCache(), false
        );
        final MetadataEventQueues events = new MetadataEventQueues(
            new LinkedList<>(), this.service
        );
        final Optional<Queue<ProxyArtifactEvent>> first = events.proxyEventQueues(cfg);
        MatcherAssert.assertThat("Proxy queue should be present", first.isPresent());
        final Optional<Queue<ProxyArtifactEvent>> second = events.proxyEventQueues(cfg);
        MatcherAssert.assertThat(
            "After second call the same queue is returned",
            first.get(), new IsEqual<>(second.orElseThrow())
        );
        Thread.sleep(2000);
        final List<String> groups = new StdSchedulerFactory().getScheduler().getJobGroupNames();
        MatcherAssert.assertThat(
            "Only one job group exists",
            groups.size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "Only one job exists in this group",
            new StdSchedulerFactory().getScheduler()
                .getJobKeys(GroupMatcher.groupEquals(groups.get(0))).size(),
            new IsEqual<>(1)
        );
    }

    @Test
    void createsQueueAndStartsGivenAmountOfJobs() throws SchedulerException, InterruptedException {
        final RepoConfig cfg = RepoConfig.from(
            new RepoConfigYaml("maven-proxy").withFileStorage(Path.of("a/b/c")).withSettings(
                Yaml.createYamlMappingBuilder().add("threads_count", "4")
                    .add("interval_seconds", "5").build()
            ).yaml(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From("my-maven-proxy"),
            new TestStoragesCache(), false
        );
        final MetadataEventQueues events =
            new MetadataEventQueues(new LinkedList<>(), this.service);
        final Optional<Queue<ProxyArtifactEvent>> queue = events.proxyEventQueues(cfg);
        MatcherAssert.assertThat("Proxy queue should be present", queue.isPresent());
        Thread.sleep(2000);
        final List<String> groups = new StdSchedulerFactory().getScheduler().getJobGroupNames();
        MatcherAssert.assertThat(
            "Only one job group exists",
            groups.size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "Only one job exists in this group",
            new StdSchedulerFactory().getScheduler()
                .getJobKeys(GroupMatcher.groupEquals(groups.get(0))).size(),
            new IsEqual<>(4)
        );
    }

}
