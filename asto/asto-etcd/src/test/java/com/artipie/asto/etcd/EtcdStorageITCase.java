/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

package com.artipie.asto.etcd;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.blocking.BlockingStorage;
import io.etcd.jetcd.Client;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.testcontainers.containers.wait.strategy.Wait;
import java.time.Duration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Test case for etcd-storage.
 */
@DisabledOnOs(OS.WINDOWS)
final class EtcdStorageITCase {

    /**
     * Test etcd container using multi-arch image.
     */
    @SuppressWarnings("resource")
    static final GenericContainer<?> ETCD = new GenericContainer<>(
        DockerImageName.parse("quay.io/coreos/etcd:v3.5.17")
    )
        .withCommand(
            "/usr/local/bin/etcd",
            "--listen-client-urls", "http://0.0.0.0:2379",
            "--advertise-client-urls", "http://0.0.0.0:2379"
        )
        .withExposedPorts(2379)
        .waitingFor(
            Wait.forLogMessage(".*ready to serve client requests.*\n", 1)
                .withStartupTimeout(Duration.ofMinutes(2))
        );

    /**
     * Storage.
     */
    private Storage storage;

    @BeforeAll
    static void beforeAll() {
        ETCD.start();
    }

    @BeforeEach
    void setUp() {
        final String endpoint = String.format(
            "http://%s:%d",
            ETCD.getHost(),
            ETCD.getMappedPort(2379)
        );
        this.storage = new EtcdStorage(
            Client.builder().endpoints(URI.create(endpoint)).build(),
            endpoint
        );
    }

    @AfterAll
    static void afterAll() {
        ETCD.close();
    }

    @Test
    void listsItems() {
        final Key one = new Key.From("one");
        final Key two = new Key.From("a/two");
        final Key three = new Key.From("a/three");
        this.storage.save(
            one,
            new Content.From("data 1".getBytes(StandardCharsets.UTF_8))
        ).join();
        this.storage.save(
            two,
            new Content.From("data 2".getBytes(StandardCharsets.UTF_8))
        ).join();
        this.storage.save(
            three,
            new Content.From("data 3".getBytes(StandardCharsets.UTF_8))
        ).join();
        MatcherAssert.assertThat(
            "Should list all items",
            new BlockingStorage(this.storage).list(Key.ROOT),
            Matchers.hasItems(one, two, three)
        );
        MatcherAssert.assertThat(
            "Should list prefixed items",
            new BlockingStorage(this.storage).list(new Key.From("a")),
            Matchers.hasItems(two, three)
        );
    }

    @Test
    void readAndWrite() {
        final Key key = new Key.From("one", "two", "three");
        final byte[] data = "some binary data".getBytes();
        final BlockingStorage bsto = new BlockingStorage(this.storage);
        bsto.save(key, "first revision".getBytes());
        bsto.save(key, "second revision".getBytes());
        bsto.save(key, data);
        MatcherAssert.assertThat(bsto.value(key), Matchers.equalTo(data));
    }

    @Test
    @SuppressWarnings("deprecation")
    void getSize() {
        final Key key = new Key.From("another", "key");
        final byte[] data = "data with size".getBytes();
        final BlockingStorage bsto = new BlockingStorage(this.storage);
        bsto.save(key, data);
        MatcherAssert.assertThat(bsto.size(key), Matchers.equalTo((long) data.length));
    }

    @Test
    void checkExist() {
        final Key key = new Key.From("existing", "item");
        final byte[] data = "I exist".getBytes();
        final BlockingStorage bsto = new BlockingStorage(this.storage);
        bsto.save(key, data);
        MatcherAssert.assertThat(bsto.exists(key), Matchers.is(true));
    }

    @Test
    void move() {
        final BlockingStorage bsto = new BlockingStorage(this.storage);
        final Key src = new Key.From("source");
        final Key dst = new Key.From("destination");
        final byte[] data = "data to move".getBytes();
        bsto.save(src, data);
        bsto.move(src, dst);
        MatcherAssert.assertThat("source still exist", bsto.exists(src), new IsEqual<>(false));
        MatcherAssert.assertThat("source was not moved", bsto.value(dst), new IsEqual<>(data));
    }

    @Test
    void delete() {
        final BlockingStorage bsto = new BlockingStorage(this.storage);
        final Key key = new Key.From("temporary");
        final byte[] data = "data to delete".getBytes();
        bsto.save(key, data);
        bsto.delete(key);
        MatcherAssert.assertThat(bsto.exists(key), new IsEqual<>(false));
    }

    @Test
    void failsIfNothingToDelete() {
        final BlockingStorage bsto = new BlockingStorage(this.storage);
        final Key key = new Key.From("nothing");
        final CompletionException cex = Assertions.assertThrows(
            CompletionException.class,
            () -> bsto.delete(key)
        );
        MatcherAssert.assertThat(
            cex.getCause().getCause().getMessage(),
            new IsEqual<>(String.format("No value for key: %s", key))
        );
    }

    @Test
    void returnsIdentifier() {
        final String endpoint = String.format(
            "http://%s:%d",
            ETCD.getHost(),
            ETCD.getMappedPort(2379)
        );
        MatcherAssert.assertThat(
            this.storage.identifier(),
            Matchers.stringContainsInOrder("Etcd", endpoint)
        );
    }
}
