/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.etcd;

import com.artipie.asto.Storage;
import com.artipie.asto.test.StorageWhiteboxVerification;
import io.etcd.jetcd.Client;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * ETCD storage verification test.
 *
 * @since 0.1
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
@DisabledOnOs(OS.WINDOWS)
@Disabled("FOR_REMOVING")
public final class EtcdStorageVerificationTest extends StorageWhiteboxVerification {
    /**
     * Etcd container.
     */
    @SuppressWarnings("resource")
    private static GenericContainer<?> etcd;

    @Override
    protected Storage newStorage() {
        final String endpoint = String.format(
            "http://%s:%d",
            EtcdStorageVerificationTest.etcd.getHost(),
            EtcdStorageVerificationTest.etcd.getMappedPort(2379)
        );
        return new EtcdStorage(
            Client.builder().endpoints(URI.create(endpoint)).build(),
            endpoint
        );
    }

    @Override
    protected Optional<Storage> newBaseForRootSubStorage() {
        return Optional.empty();
    }

    @BeforeAll
    static void beforeClass() {
        EtcdStorageVerificationTest.etcd = new GenericContainer<>(
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
        EtcdStorageVerificationTest.etcd.start();
    }

    @AfterAll
    static void afterClass() {
        EtcdStorageVerificationTest.etcd.stop();
    }
}
