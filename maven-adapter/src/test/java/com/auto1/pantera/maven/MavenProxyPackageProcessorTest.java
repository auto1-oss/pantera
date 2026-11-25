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
import com.auto1.pantera.scheduling.ProxyEventRetry;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

/**
 * Test for {@link MavenProxyPackageProcessorTest}.
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
     * Scheduler.
     */
    private Scheduler scheduler;

    /**
     * Job data map.
     */
    private JobDataMap data;

    @BeforeEach
    void init() throws SchedulerException {
        this.asto = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
        this.packages = new ConcurrentLinkedQueue<>();
        this.scheduler = new StdSchedulerFactory().getScheduler();
        this.data = new JobDataMap();
        this.data.put("events", this.events);
        this.data.put("packages", this.packages);
        this.data.put("storage", this.asto);
    }

    @Test
    void processesPackage() throws SchedulerException {
        final String pkg = "com/pantera/asto/0.15";
        final Key key = new Key.From(pkg);
        new TestResource(pkg).addFilesTo(this.asto, key);
        this.packages.add(new ProxyArtifactEvent(key, MavenProxyPackageProcessorTest.RNAME));
        this.packages.add(new ProxyArtifactEvent(key, MavenProxyPackageProcessorTest.RNAME));
        this.packages.add(new ProxyArtifactEvent(key, MavenProxyPackageProcessorTest.RNAME));
        this.scheduler.scheduleJob(
            JobBuilder.newJob(MavenProxyPackageProcessor.class).setJobData(this.data).withIdentity(
                "job1", MavenProxyPackageProcessor.class.getSimpleName()
            ).build(),
            TriggerBuilder.newTrigger().startNow()
                .withIdentity("trigger1", MavenProxyPackageProcessor.class.getSimpleName()).build()
        );
        this.scheduler.start();
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
    void processesSeveralPackagesAndPacakgeWithError() throws SchedulerException {
        final String first = "com/pantera/asto/0.20.1";
        final Key firstk = new Key.From(first);
        new TestResource(first).addFilesTo(this.asto, firstk);
        final String second = "com/pantera/helloworld/0.1";
        final Key secondk = new Key.From(second);
        new TestResource(second).addFilesTo(this.asto, secondk);
        final String snapshot = "com/pantera/asto/1.0-SNAPSHOT";
        final Key snapshotk = new Key.From(snapshot);
        new TestResource(snapshot).addFilesTo(this.asto, snapshotk);
        this.scheduler.scheduleJob(
            JobBuilder.newJob(MavenProxyPackageProcessor.class).setJobData(this.data).withIdentity(
                "job1", MavenProxyPackageProcessor.class.getSimpleName()
            ).build(),
            TriggerBuilder.newTrigger().startNow()
                .withIdentity("trigger1", MavenProxyPackageProcessor.class.getSimpleName()).build()
        );
        this.scheduler.start();
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

    @Test
    void givesUpOnAFileThatVanishedAfterListing() {
        final Key key = new Key.From("com/pantera/asto/0.15");
        ProxyArtifactEvent event =
            new ProxyArtifactEvent(key, MavenProxyPackageProcessorTest.RNAME);
        for (int idx = 0; idx < ProxyEventRetry.MAX_ATTEMPTS; idx += 1) {
            event = event.retried(0L);
        }
        this.packages.add(event);
        // Quartz builds a new job instance for every run, so the attempt
        // count has to come with the event.
        final MavenProxyPackageProcessor processor = new MavenProxyPackageProcessor();
        processor.setEvents(this.events);
        processor.setPackages(this.packages);
        processor.setStorage(new ListedButGone(key));
        processor.execute(null);
        MatcherAssert.assertThat(
            "an exhausted event is dropped, not put back",
            this.packages.isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    void countsRetriesAcrossJobInstances() {
        final Key key = new Key.From("com/pantera/asto/0.15");
        this.packages.add(new ProxyArtifactEvent(key, MavenProxyPackageProcessorTest.RNAME));
        for (int run = 0; run < 3; run += 1) {
            final MavenProxyPackageProcessor processor = new MavenProxyPackageProcessor();
            processor.setEvents(this.events);
            processor.setPackages(this.packages);
            processor.setStorage(new ListedButGone(key));
            processor.execute(null);
        }
        MatcherAssert.assertThat(
            "the event stays queued", this.packages.size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the attempt is counted on the event and runs inside the wait leave it alone",
            this.packages.peek().attempts(), new IsEqual<>(1)
        );
    }

    /**
     * Storage that lists a jar which is gone by the time it is read.
     */
    private static final class ListedButGone extends Storage.Wrap {

        /**
         * Version directory.
         */
        private final Key dir;

        ListedButGone(final Key dir) {
            super(new InMemoryStorage());
            this.dir = dir;
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return CompletableFuture.completedFuture(
                List.of(new Key.From(this.dir, "asto-0.15.jar"))
            );
        }
    }

}
