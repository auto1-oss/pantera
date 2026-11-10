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
package com.auto1.pantera.npm.events;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link NpmProxyPackageProcessor}.
 * <p>
 * As of the WS2.2b fix, this processor no longer implements {@code org.quartz.Job}
 * — it runs as a plain {@link Runnable} tick on a per-node scheduler (see
 * {@code MetadataEventQueues}/{@code LocalEventDrainScheduler}), so these tests
 * wire and invoke it directly instead of scheduling it through a real Quartz
 * {@code Scheduler}.
 * @since 1.5
 */
class NpmProxyPackageProcessorTest {

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
    private NpmProxyPackageProcessor processor;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
        this.events = new LinkedList<>();
        this.packages = new LinkedList<>();
        this.processor = new NpmProxyPackageProcessor();
        this.processor.setEvents(this.events);
        this.processor.setPackages(this.packages);
        this.processor.setStorage(this.asto);
        this.processor.setHost("localhost");
    }

    @Test
    void addsEvents() {
        this.saveFilesToRegistry();
        this.packages.add(
            new ProxyArtifactEvent(
                new Key.From(
                    "@hello/simple-npm-project", "-", "@hello/simple-npm-project-1.0.1.tgz"
                ),
                "my-npm"
            )
        );
        this.processor.run();
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> this.events.size() == 1);
    }

    @Test
    void doesNotAddsItemToQueueIfTgzNotExists() {
        this.packages.add(new ProxyArtifactEvent(new Key.From("not-existing.tgz"), "npm-proxy"));
        this.processor.run();
        Awaitility.await().pollDelay(8, TimeUnit.SECONDS).until(() -> this.events.size() == 0);
    }

    private void saveFilesToRegistry() {
        new TestResource("storage/@hello/simple-npm-project/meta.json").saveTo(
            this.asto,
            new Key.From("@hello/simple-npm-project", "meta.json")
        );
        new TestResource(
            "storage/@hello/simple-npm-project/-/@hello/simple-npm-project-1.0.1.tgz"
        ).saveTo(
            this.asto,
            new Key.From("@hello/simple-npm-project", "-", "@hello/simple-npm-project-1.0.1.tgz")
        );
    }

}
