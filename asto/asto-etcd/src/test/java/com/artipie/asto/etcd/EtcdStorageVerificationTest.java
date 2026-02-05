/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.etcd;

import com.artipie.asto.Storage;
import com.artipie.asto.test.StorageWhiteboxVerification;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.test.EtcdClusterExtension;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;

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
     * Etcd cluster.
     */
    @RegisterExtension
    static final EtcdClusterExtension ETCD = EtcdClusterExtension.builder()
        .withNodes(1)
        .build();

    @Override
    protected Storage newStorage() {
        final List<URI> endpoints = ETCD.clientEndpoints();
        return new EtcdStorage(
            Client.builder().endpoints(endpoints).build(),
            endpoints.stream().map(URI::toString).collect(Collectors.joining())
        );
    }

    @Override
    protected Optional<Storage> newBaseForRootSubStorage() {
        return Optional.empty();
    }
}
