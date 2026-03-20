/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.cache.CacheDocker;
import com.auto1.pantera.docker.composite.MultiReadDocker;
import com.auto1.pantera.docker.composite.ReadWriteDocker;
import com.auto1.pantera.docker.junit.DockerClient;
import com.auto1.pantera.docker.junit.DockerClientSupport;
import com.auto1.pantera.docker.junit.DockerRepository;
import com.auto1.pantera.docker.proxy.ProxyDocker;
import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.http.client.auth.AuthClientSlice;
import com.auto1.pantera.http.client.auth.GenericAuthenticator;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import com.google.common.base.Stopwatch;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Integration test for {@link ProxyDocker}.
 */
@DockerClientSupport
@DisabledOnOs(OS.WINDOWS)
final class CachingProxyITCase {

    /**
     * Example image to use in tests.
     */
    private Image img;

    /**
     * Docker client.
     */
    private DockerClient cli;

    /**
     * Docker cache.
     */
    private Docker cache;

    /**
     * HTTP client used for proxy.
     */
    private JettyClientSlices client;

    /**
     * Docker repository.
     */
    private DockerRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        this.img = new Image.ForOs();
        this.client = new JettyClientSlices(
            new HttpClientSettings().setFollowRedirects(true)
        );
        this.client.start();
        this.cache = new AstoDocker("test_registry", new InMemoryStorage());
        final Docker local = new AstoDocker("test_registry", new InMemoryStorage());
        this.repo = new DockerRepository(
            new ReadWriteDocker(
                new MultiReadDocker(
                    local,
                    new CacheDocker(
                        new MultiReadDocker(
                            new ProxyDocker("test_registry", this.client.https("mcr.microsoft.com")),
                            new ProxyDocker("test_registry",
                                new AuthClientSlice(
                                    this.client.https("registry-1.docker.io"),
                                    new GenericAuthenticator(this.client)
                                )
                            )
                        ),
                        this.cache,
                        Optional.empty(),
                        Optional.empty()
                    )
                ),
                local
            )
        );
        this.repo.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.repo != null) {
            this.repo.stop();
        }
        if (this.client != null) {
            this.client.stop();
        }
    }

    @Test
    void shouldPushAndPullLocal() throws Exception {
        final String original = this.img.remoteByDigest();
        this.cli.run("pull", original);
        final String image = String.format("%s/my-test/latest", this.repo.url());
        this.cli.run("tag", original, image);
        this.cli.run("push", image);
        this.cli.run("image", "rm", original);
        this.cli.run("image", "rm", image);
        final String output = this.cli.run("pull", image);
        MatcherAssert.assertThat(output, CachingProxyITCase.imagePulled(image));
    }

    @Test
    void shouldPullRemote() throws Exception {
        final String image = new Image.From(
            this.repo.url(), this.img.name(), this.img.digest(), this.img.layer()
        ).remoteByDigest();
        final String output = this.cli.run("pull", image);
        MatcherAssert.assertThat(output, CachingProxyITCase.imagePulled(image));
    }

    @Test
    @DisabledOnOs(OS.LINUX)
    void shouldPullWhenRemoteIsDown() throws Exception {
        final String image = new Image.From(
            this.repo.url(), this.img.name(), this.img.digest(), this.img.layer()
        ).remoteByDigest();
        this.cli.run("pull", image);
        this.awaitManifestCached();
        this.cli.run("image", "rm", image);
        this.client.stop();
        final String output = this.cli.run("pull", image);
        MatcherAssert.assertThat(output, CachingProxyITCase.imagePulled(image));
    }

    private void awaitManifestCached() throws Exception {
        final Manifests manifests = this.cache.repo(img.name()).manifests();
        final ManifestReference ref = ManifestReference.from(
            new Digest.FromString(this.img.digest())
        );
        final Stopwatch stopwatch = Stopwatch.createStarted();
        while (manifests.get(ref).toCompletableFuture().join().isEmpty()) {
            if (stopwatch.elapsed(TimeUnit.SECONDS) > TimeUnit.MINUTES.toSeconds(1)) {
                throw new IllegalStateException(
                    String.format(
                        "Manifest is expected to be present, but it was not found after %s seconds",
                        stopwatch.elapsed(TimeUnit.SECONDS)
                    )
                );
            }
            Thread.sleep(TimeUnit.SECONDS.toMillis(1));
        }
    }

    private static Matcher<String> imagePulled(final String image) {
        return new StringContains(
            false,
            String.format("Status: Downloaded newer image for %s", image)
        );
    }
}
