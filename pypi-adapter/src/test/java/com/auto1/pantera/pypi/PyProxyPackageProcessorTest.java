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
package com.auto1.pantera.pypi;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.time.Instant;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link PyProxyPackageProcessor}.
 * <p>
 * As of the WS2.2b fix, this processor no longer implements {@code org.quartz.Job}
 * — it runs as a plain {@link Runnable} tick on a per-node scheduler (see
 * {@code MetadataEventQueues}/{@code LocalEventDrainScheduler}), so these tests
 * wire and invoke it directly instead of scheduling it through a real Quartz
 * {@code Scheduler}.
 */
class PyProxyPackageProcessorTest {

    /**
     * Repository name.
     */
    private static final String REPO_NAME = "my-pypi-proxy";

    /**
     * Test storage.
     */
    private Storage asto;

    /**
     * Artifact events queue.
     */
    private Queue<ArtifactEvent> events;

    /**
     * Queue with packages and owner names.
     */
    private Queue<ProxyArtifactEvent> packages;

    /**
     * Processor under test.
     */
    private PyProxyPackageProcessor processor;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
        this.events = new LinkedList<>();
        this.packages = new LinkedList<>();
        this.processor = new PyProxyPackageProcessor();
        this.processor.setEvents(this.events);
        this.processor.setPackages(this.packages);
        this.processor.setStorage(this.asto);
    }

    @Test
    void checkPackagesAndAddsToQueue() {
        final Key zip = new Key.From("pantera-sample-0.2.zip");
        final Key tar = new Key.From("pantera-sample-0.2.tar");
        final Key whl = new Key.From("pantera_sample-0.2-py3-none-any.whl");
        new TestResource("pypi_repo/pantera-sample-0.2.zip").saveTo(this.asto, zip);
        new TestResource("pypi_repo/pantera-sample-0.2.tar").saveTo(this.asto, tar);
        new TestResource("pypi_repo/pantera_sample-0.2-py3-none-any.whl").saveTo(this.asto, whl);
        this.packages.add(new ProxyArtifactEvent(zip, PyProxyPackageProcessorTest.REPO_NAME));
        this.packages.add(new ProxyArtifactEvent(tar, PyProxyPackageProcessorTest.REPO_NAME));
        this.packages.add(new ProxyArtifactEvent(whl, PyProxyPackageProcessorTest.REPO_NAME));
        this.processor.run();
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> this.events.size() == 3);
        MatcherAssert.assertThat(
            this.events.stream()
                .map(ArtifactEvent::artifactName)
                .collect(Collectors.toSet()),
            Matchers.equalTo(Set.of("pantera-sample"))
        );
    }

    @Test
    void doNotAddNotValidPackage() {
        final Key tar = new Key.From("pantera-sample-0.2.tar");
        final Key invalid = new Key.From("invalid.zip");
        this.asto.save(invalid, Content.EMPTY).join();
        new TestResource("pypi_repo/pantera-sample-0.2.tar").saveTo(this.asto, tar);
        this.packages.add(new ProxyArtifactEvent(invalid, PyProxyPackageProcessorTest.REPO_NAME));
        this.packages.add(new ProxyArtifactEvent(tar, PyProxyPackageProcessorTest.REPO_NAME));
        this.processor.run();
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> this.events.size() == 1);
    }

    @Test
    void requeuesWhenArtifactIsMissing() {
        final ProxyArtifactEvent event =
            new ProxyArtifactEvent(new Key.From("absent-1.0.0.tar.gz"), PyProxyPackageProcessorTest.REPO_NAME);
        this.packages.add(event);
        this.processor.run();
        MatcherAssert.assertThat("No artifact events should be produced", this.events.isEmpty());
        MatcherAssert.assertThat("Original package must be re-queued", this.packages.contains(event));
        MatcherAssert.assertThat(
            "Queue keeps single pending item",
            this.packages.size(),
            Matchers.equalTo(1)
        );
    }

    @Test
    void addsReleaseInformationWhenPresent() {
        final Key wheel = new Key.From("pantera_sample-0.2-py3-none-any.whl");
        new TestResource("pypi_repo/pantera_sample-0.2-py3-none-any.whl").saveTo(this.asto, wheel);
        final long release = Instant.now().minusSeconds(90L).toEpochMilli();
        this.packages.add(
            new ProxyArtifactEvent(
                wheel,
                PyProxyPackageProcessorTest.REPO_NAME,
                "alice",
                Optional.of(release)
            )
        );
        this.processor.run();
        MatcherAssert.assertThat(this.events.size(), Matchers.equalTo(1));
        final ArtifactEvent artifact = this.events.peek();
        MatcherAssert.assertThat(
            "Release timestamp propagated to artifact event",
            artifact.releaseDate().orElseThrow(),
            Matchers.equalTo(release)
        );
    }

    @Test
    void normalizesArtifactName() {
        final Key tarball = new Key.From("AlarmTime-0.1.5.tar.gz");
        new TestResource("pypi_repo/alarmtime-0.1.5.tar.gz").saveTo(this.asto, tarball);
        this.packages.add(new ProxyArtifactEvent(tarball, PyProxyPackageProcessorTest.REPO_NAME));
        this.processor.run();
        MatcherAssert.assertThat(this.events.size(), Matchers.equalTo(1));
        final ArtifactEvent artifact = this.events.peek();
        MatcherAssert.assertThat(
            "Artifact name stored in normalized form",
            artifact.artifactName(),
            Matchers.equalTo("alarmtime")
        );
    }
}
