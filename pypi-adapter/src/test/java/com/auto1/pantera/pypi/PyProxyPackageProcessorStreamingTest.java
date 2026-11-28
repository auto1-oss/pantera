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
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import io.reactivex.Flowable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link PyProxyPackageProcessor} never holds a whole distribution file in
 * memory: the size comes from the storage, a file known by its name (wheels,
 * installers, disk images, ...) is not read at all, and the core metadata of
 * the others is read from the stored file as a stream. Installers and disk
 * images are hundreds of megabytes; some wheels are larger than a Java array.
 *
 * @since 2.2.15
 */
final class PyProxyPackageProcessorStreamingTest {

    /**
     * Repository name.
     */
    private static final String REPO = "my-pypi-proxy";

    /**
     * Artifact events queue.
     */
    private Queue<ArtifactEvent> events;

    /**
     * Queue with packages and owner names.
     */
    private Queue<ProxyArtifactEvent> packages;

    @BeforeEach
    void init() {
        this.events = new ConcurrentLinkedQueue<>();
        this.packages = new ConcurrentLinkedQueue<>();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "pantera_sample-2.1-py3-none-any.whl",
            "pantera_sample-2.1.win32.exe", "pantera_sample-2.1.win32-py2.7.msi",
            "pantera_sample-2.1-py2.7-macosx10.6.dmg", "pantera_sample-2.1-1.noarch.rpm",
            "pantera-sample_2.1-1_all.deb", "pantera-sample-2.1.tgz",
            "pantera-sample-2.1.tar.xz"
        }
    )
    void knowsAFileByItsNameWithoutReadingIt(final String file) {
        final Recording storage = new Recording(new InMemoryStorage());
        final Key key = new Key.From(file);
        final byte[] bytes = PyProxyPackageProcessorStreamingTest.random(3_000);
        storage.save(key, new Content.From(bytes)).join();
        this.process(storage, key);
        MatcherAssert.assertThat(
            "the release is recorded with the size the storage has",
            this.recorded(), new IsEqual<>(List.of("pantera-sample 2.1 " + bytes.length))
        );
        MatcherAssert.assertThat(
            "the file is not read", storage.reads(), new IsEqual<>(List.of())
        );
    }

    @ParameterizedTest
    @CsvSource({
        "pypi_repo/pantera-sample-0.2.tar.gz,pantera-sample-0.2.tar.gz,0.2",
        "pypi_repo/pantera-sample-2.1.tar.bz2,pantera-sample-2.1.tar.bz2,2.1",
        "pypi_repo/pantera_sample-2.1-py3.7.egg,pantera_sample-2.1-py3.7.egg,2.1"
    })
    void readsTheMetadataWithoutAskingForTheWholeFile(
        final String resource, final String file, final String version
    ) {
        final Recording storage = new Recording(new InMemoryStorage());
        final Key key = new Key.From(file);
        new TestResource(resource).saveTo(storage, key);
        final long size = storage.size(key).join();
        this.process(storage, key);
        MatcherAssert.assertThat(
            "the release is recorded with the size the storage has",
            this.recorded(), new IsEqual<>(List.of("pantera-sample " + version + " " + size))
        );
        MatcherAssert.assertThat(
            "the file is read once", storage.reads(), new IsEqual<>(List.of(file))
        );
        MatcherAssert.assertThat(
            "as a stream: a few chunks are asked for at a time, never the whole file",
            storage.largestRequest() < Long.MAX_VALUE, new IsEqual<>(true)
        );
    }

    @Test
    void readsTheMetadataOfALargeSdistOnFileStorage(@TempDir final Path dir) throws Exception {
        final Storage storage = new FileStorage(dir);
        final Key key = new Key.From("big-pkg-1.0.tar.gz");
        final byte[] sdist = PyProxyPackageProcessorStreamingTest.sdist();
        storage.save(key, new Content.From(sdist)).join();
        this.process(storage, key);
        MatcherAssert.assertThat(
            this.recorded(), new IsEqual<>(List.of("big-pkg 1.0 " + sdist.length))
        );
    }

    @Test
    void readsTheMetadataOfAnSdistWithBytesAfterTheArchive(@TempDir final Path dir)
        throws Exception {
        // The gzip reader stops at the end of the compressed archive; what
        // follows it is never read by the archive reader.
        final Storage storage = new FileStorage(dir);
        final Key key = new Key.From("big-pkg-1.0.tar.gz");
        final byte[] archive = PyProxyPackageProcessorStreamingTest.sdist();
        final byte[] sdist = Arrays.copyOf(archive, archive.length + 512 * 1024);
        storage.save(key, new Content.From(sdist)).join();
        this.process(storage, key);
        MatcherAssert.assertThat(
            this.recorded(), new IsEqual<>(List.of("big-pkg 1.0 " + sdist.length))
        );
    }

    @Test
    void knowsAnSdistWithoutMetadataByItsName(@TempDir final Path dir) throws Exception {
        // Old sdists were not all built by setuptools: Twisted 10.0.0, for
        // one, carries no PKG-INFO. The upstream lists the file under its
        // name, which then says the project and the version.
        final Storage storage = new FileStorage(dir);
        final Key key = new Key.From("big-pkg-1.0.tar.gz");
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar =
            new TarArchiveOutputStream(new GzipCompressorOutputStream(out))) {
            PyProxyPackageProcessorStreamingTest.entry(
                tar, "big-pkg-1.0/setup.py",
                "from distutils.core import setup\n".getBytes(StandardCharsets.UTF_8)
            );
        }
        final byte[] sdist = out.toByteArray();
        storage.save(key, new Content.From(sdist)).join();
        this.process(storage, key);
        MatcherAssert.assertThat(
            this.recorded(), new IsEqual<>(List.of("big-pkg 1.0 " + sdist.length))
        );
    }

    /**
     * Run the processor over one stored file.
     * @param storage Storage
     * @param key Key of the file
     */
    private void process(final Storage storage, final Key key) {
        final PyProxyPackageProcessor processor = new PyProxyPackageProcessor();
        processor.setEvents(this.events);
        processor.setPackages(this.packages);
        processor.setStorage(storage);
        this.packages.add(new ProxyArtifactEvent(key, PyProxyPackageProcessorStreamingTest.REPO));
        processor.run();
    }

    /**
     * Recorded releases.
     * @return Releases as {@code <project> <version> <size>}
     */
    private List<String> recorded() {
        return this.events.stream()
            .map(
                event -> String.format(
                    "%s %s %d", event.artifactName(), event.artifactVersion(), event.size()
                )
            )
            .collect(Collectors.toList());
    }

    /**
     * Source archive larger than 1 MB.
     * @return Bytes
     * @throws IOException On error
     */
    private static byte[] sdist() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar =
            new TarArchiveOutputStream(new GzipCompressorOutputStream(out))) {
            PyProxyPackageProcessorStreamingTest.entry(
                tar, "big-pkg-1.0/PKG-INFO", PyProxyPackageProcessorStreamingTest.metadata()
            );
            PyProxyPackageProcessorStreamingTest.entry(
                tar, "big-pkg-1.0/data.bin",
                PyProxyPackageProcessorStreamingTest.random(2 * 1024 * 1024)
            );
        }
        return out.toByteArray();
    }

    /**
     * Add a tar entry.
     * @param tar Archive
     * @param name Entry name
     * @param bytes Entry bytes
     * @throws IOException On error
     */
    private static void entry(final TarArchiveOutputStream tar, final String name,
        final byte[] bytes) throws IOException {
        final TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }

    /**
     * Core metadata of {@code big-pkg 1.0}.
     * @return Bytes
     */
    private static byte[] metadata() {
        return "Metadata-Version: 2.1\nName: big-pkg\nVersion: 1.0\nSummary: big\n"
            .getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Incompressible bytes.
     * @param size Size
     * @return Bytes
     */
    private static byte[] random(final int size) {
        final byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }

    /**
     * Storage that records which files are read and how much of a file a
     * reader asks for at once.
     */
    private static final class Recording extends Storage.Wrap {

        /**
         * Keys read.
         */
        private final Queue<String> read = new ConcurrentLinkedQueue<>();

        /**
         * Largest single request of a reader.
         */
        private final AtomicLong largest = new AtomicLong();

        /**
         * Ctor.
         * @param origin Storage
         */
        Recording(final Storage origin) {
            super(origin);
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            this.read.add(key.string());
            return super.value(key).thenApply(
                content -> new Content.From(
                    content.size(),
                    Flowable.fromPublisher(content).doOnRequest(
                        count -> this.largest.accumulateAndGet(count, Math::max)
                    )
                )
            );
        }

        /**
         * Keys read.
         * @return Keys
         */
        List<String> reads() {
            return List.copyOf(this.read);
        }

        /**
         * Largest single request of a reader.
         * @return Number of chunks
         */
        long largestRequest() {
            return this.largest.get();
        }
    }
}
