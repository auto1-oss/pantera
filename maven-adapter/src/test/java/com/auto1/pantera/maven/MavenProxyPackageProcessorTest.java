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
package com.auto1.pantera.maven;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link MavenProxyPackageProcessorTest}.
 * <p>
 * As of the WS2.2b fix, this processor no longer implements {@code org.quartz.Job}
 * — it runs as a plain {@link Runnable} tick on a per-node scheduler (see
 * {@code MetadataEventQueues}/{@code LocalEventDrainScheduler}), so these tests
 * wire and invoke it directly instead of scheduling it through a real Quartz
 * {@code Scheduler}.
 */
class MavenProxyPackageProcessorTest {

    /**
     * Repository name.
     */
    private static final String RNAME = "my-maven-proxy";

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
    private MavenProxyPackageProcessor processor;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
        this.events = new LinkedList<>();
        this.packages = new LinkedList<>();
        this.processor = new MavenProxyPackageProcessor();
        this.processor.setEvents(this.events);
        this.processor.setPackages(this.packages);
        this.processor.setStorage(this.asto);
    }

    @Test
    void processesPackage() {
        final String pkg = "com/pantera/asto/0.15";
        final Key key = new Key.From(pkg);
        new TestResource(pkg).addFilesTo(this.asto, key);
        this.packages.add(new ProxyArtifactEvent(key, MavenProxyPackageProcessorTest.RNAME));
        this.packages.add(new ProxyArtifactEvent(key, MavenProxyPackageProcessorTest.RNAME));
        this.packages.add(new ProxyArtifactEvent(key, MavenProxyPackageProcessorTest.RNAME));
        this.processor.run();
        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> this.events.size() == 1);
        MatcherAssert.assertThat(
            "Same items were removed from packages queue", this.packages.isEmpty()
        );
        final ArtifactEvent event = this.events.poll();
        MatcherAssert.assertThat(event.artifactName(), new IsEqual<String>("com.pantera.asto"));
        MatcherAssert.assertThat(event.artifactVersion(), new IsEqual<String>("0.15"));
    }

    @Test
    @Disabled("https://github.com/pantera/pantera/issues/1349")
    void processesSeveralPackagesAndPacakgeWithError() {
        final String first = "com/pantera/asto/0.20.1";
        final Key firstk = new Key.From(first);
        new TestResource(first).addFilesTo(this.asto, firstk);
        final String second = "com/pantera/helloworld/0.1";
        final Key secondk = new Key.From(second);
        new TestResource(second).addFilesTo(this.asto, secondk);
        final String snapshot = "com/pantera/asto/1.0-SNAPSHOT";
        final Key snapshotk = new Key.From(snapshot);
        new TestResource(snapshot).addFilesTo(this.asto, snapshotk);
        this.processor.run();
        this.packages.add(new ProxyArtifactEvent(firstk, MavenProxyPackageProcessorTest.RNAME));
        this.packages.add(new ProxyArtifactEvent(snapshotk, MavenProxyPackageProcessorTest.RNAME));
        this.packages.add(new ProxyArtifactEvent(secondk, MavenProxyPackageProcessorTest.RNAME));
        this.packages.add(
            new ProxyArtifactEvent(new Key.From("fake"), MavenProxyPackageProcessorTest.RNAME)
        );
        this.packages.add(new ProxyArtifactEvent(snapshotk, MavenProxyPackageProcessorTest.RNAME));
        this.packages.add(new ProxyArtifactEvent(firstk, MavenProxyPackageProcessorTest.RNAME));
        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> this.events.size() == 3);
        MatcherAssert.assertThat(
            "Same items were removed from packages queue", this.packages.isEmpty()
        );
    }

}
