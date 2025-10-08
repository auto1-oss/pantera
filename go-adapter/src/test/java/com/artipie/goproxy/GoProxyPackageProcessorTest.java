/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.goproxy;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.scheduling.ProxyArtifactEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Test for {@link GoProxyPackageProcessor}.
 */
class GoProxyPackageProcessorTest {

    private Storage storage;
    private ConcurrentLinkedQueue<ArtifactEvent> events;
    private ConcurrentLinkedQueue<ProxyArtifactEvent> packages;
    private GoProxyPackageProcessor processor;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
        this.packages = new ConcurrentLinkedQueue<>();
        this.processor = new GoProxyPackageProcessor();
        this.processor.setStorage(this.storage);
        this.processor.setEvents(this.events);
        this.processor.setPackages(this.packages);
    }

    @Test
    void processesGoModuleArtifact() {
        // Arrange: Create a Go module artifact in storage
        final String modulePath = "github.com/google/uuid";
        final String version = "1.3.0";
        // Event key format: module/@v/version (without 'v' prefix)
        final Key eventKey = new Key.From(modulePath + "/@v/" + version);
        // File key format: module/@v/vX.Y.Z.zip (with 'v' prefix)
        final Key zipKey = new Key.From(modulePath, "@v", "v" + version + ".zip");
        
        this.storage.save(
            zipKey,
            new Content.From("module content".getBytes(StandardCharsets.UTF_8))
        ).join();

        // Add proxy event
        this.packages.add(
            new ProxyArtifactEvent(
                eventKey,
                "go_proxy",
                "testuser",
                Optional.empty()
            )
        );

        // Act: Process the queue
        final JobExecutionContext context = mock(JobExecutionContext.class);
        this.processor.execute(context);

        // Assert: Verify artifact event was created
        assertEquals(1, this.events.size(), "Should have one artifact event");
        final ArtifactEvent event = this.events.poll();
        assertEquals("go-proxy", event.repoType());
        assertEquals("go_proxy", event.repoName());
        assertEquals(modulePath, event.artifactName());
        assertEquals(version, event.artifactVersion());
        assertEquals("testuser", event.owner());
    }

    @Test
    void skipsNonZipFiles() {
        // Arrange: Create .info and .mod files (not .zip)
        final String modulePath = "github.com/example/module";
        final String version = "1.0.0";
        final Key eventKey = new Key.From(modulePath + "/@v/" + version);
        
        this.storage.save(
            new Key.From(modulePath, "@v", "v1.0.0.info"),
            new Content.From("{}".getBytes(StandardCharsets.UTF_8))
        ).join();
        
        this.storage.save(
            new Key.From(modulePath, "@v", "v1.0.0.mod"),
            new Content.From("module...".getBytes(StandardCharsets.UTF_8))
        ).join();

        this.packages.add(
            new ProxyArtifactEvent(
                eventKey,
                "go_proxy",
                "testuser",
                Optional.empty()
            )
        );

        // Act
        final JobExecutionContext context = mock(JobExecutionContext.class);
        this.processor.execute(context);

        // Assert: No events should be created yet (no .zip file), and event remains queued
        assertEquals(0, this.events.size(), "Should have no events without .zip file");
        assertEquals(1, this.packages.size(), "Event should remain queued for retry");
    }

    @Test
    void handlesMultipleModules() {
        // Arrange: Create multiple modules
        final String[] modules = {
            "github.com/google/uuid",
            "golang.org/x/text",
            "github.com/stretchr/testify"
        };
        
        for (String module : modules) {
            final Key zipKey = new Key.From(module, "@v", "v1.0.0.zip");
            this.storage.save(
                zipKey,
                new Content.From("content".getBytes(StandardCharsets.UTF_8))
            ).join();
            
            this.packages.add(
                new ProxyArtifactEvent(
                    new Key.From(module + "/@v/1.0.0"),
                    "go_proxy",
                    "user",
                    Optional.empty()
                )
            );
        }

        // Act
        final JobExecutionContext context = mock(JobExecutionContext.class);
        this.processor.execute(context);

        // Assert
        assertEquals(3, this.events.size(), "Should have three artifact events");
    }
}
