/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

/**
 * Test for {@link PyProxyPackageProcessor}.
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
        this.events = new LinkedList<>();
        this.packages = new LinkedList<>();
        this.scheduler = new StdSchedulerFactory().getScheduler();
        this.data = new JobDataMap();
        this.data.put("events", this.events);
        this.data.put("packages", this.packages);
        this.data.put("storage", this.asto);
    }

    @Test
    void checkPackagesAndAddsToQueue() throws SchedulerException {
        final Key zip = new Key.From("pantera-sample-0.2.zip");
        final Key tar = new Key.From("pantera-sample-0.2.tar");
        final Key whl = new Key.From("pantera_sample-0.2-py3-none-any.whl");
        new TestResource("pypi_repo/pantera-sample-0.2.zip").saveTo(this.asto, zip);
        new TestResource("pypi_repo/pantera-sample-0.2.tar").saveTo(this.asto, tar);
        new TestResource("pypi_repo/pantera_sample-0.2-py3-none-any.whl").saveTo(this.asto, whl);
        this.packages.add(new ProxyArtifactEvent(zip, PyProxyPackageProcessorTest.REPO_NAME));
        this.packages.add(new ProxyArtifactEvent(tar, PyProxyPackageProcessorTest.REPO_NAME));
        this.packages.add(new ProxyArtifactEvent(whl, PyProxyPackageProcessorTest.REPO_NAME));
        this.scheduler.scheduleJob(
            JobBuilder.newJob(PyProxyPackageProcessor.class).setJobData(this.data).withIdentity(
                "job1", PyProxyPackageProcessor.class.getSimpleName()
            ).build(),
            TriggerBuilder.newTrigger().startNow()
                .withIdentity("trigger1", PyProxyPackageProcessor.class.getSimpleName()).build()
        );
        this.scheduler.start();
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> this.events.size() == 3);
        MatcherAssert.assertThat(
            this.events.stream()
                .map(ArtifactEvent::artifactName)
                .collect(Collectors.toSet()),
            Matchers.equalTo(Set.of("pantera-sample"))
        );
    }

    @Test
    void doNotAddNotValidPackage() throws SchedulerException {
        final Key tar = new Key.From("pantera-sample-0.2.tar");
        final Key invalid = new Key.From("invalid.zip");
        this.asto.save(invalid, Content.EMPTY).join();
        new TestResource("pypi_repo/pantera-sample-0.2.tar").saveTo(this.asto, tar);
        this.packages.add(new ProxyArtifactEvent(invalid, PyProxyPackageProcessorTest.REPO_NAME));
        this.packages.add(new ProxyArtifactEvent(tar, PyProxyPackageProcessorTest.REPO_NAME));
        this.scheduler.scheduleJob(
            JobBuilder.newJob(PyProxyPackageProcessor.class).setJobData(this.data).withIdentity(
                "job1", PyProxyPackageProcessor.class.getSimpleName()
            ).build(),
            TriggerBuilder.newTrigger().startNow()
                .withIdentity("trigger1", PyProxyPackageProcessor.class.getSimpleName()).build()
        );
        this.scheduler.start();
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> this.events.size() == 1);
    }

    @Test
    void requeuesWhenArtifactIsMissing() {
        final PyProxyPackageProcessor processor = new PyProxyPackageProcessor();
        processor.setEvents(this.events);
        processor.setPackages(this.packages);
        processor.setStorage(this.asto);
        final ProxyArtifactEvent event =
            new ProxyArtifactEvent(new Key.From("absent-1.0.0.tar.gz"), PyProxyPackageProcessorTest.REPO_NAME);
        this.packages.add(event);
        processor.execute(null);
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
        final PyProxyPackageProcessor processor = new PyProxyPackageProcessor();
        processor.setEvents(this.events);
        processor.setPackages(this.packages);
        processor.setStorage(this.asto);
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
        processor.execute(null);
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
        final PyProxyPackageProcessor processor = new PyProxyPackageProcessor();
        processor.setEvents(this.events);
        processor.setPackages(this.packages);
        processor.setStorage(this.asto);
        final Key tarball = new Key.From("AlarmTime-0.1.5.tar.gz");
        new TestResource("pypi_repo/alarmtime-0.1.5.tar.gz").saveTo(this.asto, tarball);
        this.packages.add(new ProxyArtifactEvent(tarball, PyProxyPackageProcessorTest.REPO_NAME));
        processor.execute(null);
        MatcherAssert.assertThat(this.events.size(), Matchers.equalTo(1));
        final ArtifactEvent artifact = this.events.peek();
        MatcherAssert.assertThat(
            "Artifact name stored in normalized form",
            artifact.artifactName(),
            Matchers.equalTo("alarmtime")
        );
    }

    @AfterEach
    void stop() throws SchedulerException {
        this.scheduler.shutdown();
    }
}
