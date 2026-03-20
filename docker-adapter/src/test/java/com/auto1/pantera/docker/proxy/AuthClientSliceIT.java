/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.proxy;

import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.http.client.auth.AuthClientSlice;
import com.auto1.pantera.http.client.auth.GenericAuthenticator;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * Integration test for {@link AuthClientSlice}.
 */
class AuthClientSliceIT {

    /**
     * HTTP client used for proxy.
     */
    private JettyClientSlices client;

    /**
     * Repository URL.
     */
    private AuthClientSlice slice;

    @BeforeEach
    void setUp() {
        this.client = new JettyClientSlices();
        this.client.start();
        this.slice = new AuthClientSlice(
            this.client.https("registry-1.docker.io"),
            new GenericAuthenticator(this.client)
        );
    }

    @AfterEach
    void tearDown() {
        this.client.stop();
    }

    @Test
    void getManifestByTag() {
        final ProxyManifests manifests = new ProxyManifests(this.slice, "library/busybox");
        final ManifestReference ref = ManifestReference.fromTag("latest");
        final Optional<Manifest> manifest = manifests.get(ref).toCompletableFuture().join();
        MatcherAssert.assertThat(
            manifest.isPresent(),
            new IsEqual<>(true)
        );
    }
}
