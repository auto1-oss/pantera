/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker;

import com.auto1.pantera.test.TestDockerClient;
import com.auto1.pantera.test.vertxmain.TestVertxMain;
import com.auto1.pantera.test.vertxmain.TestVertxMainBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Docker-proxy tests.
 */
public class DockerProxyCacheIT {

    private static final String IMAGE = "alpine:3.19.1";

    @TempDir
    Path temp;

    private TestVertxMain remote;

    private TestVertxMain proxy;

    private TestDockerClient alice;

    private TestDockerClient bob;

    @BeforeEach
    void setUp() throws Exception {
        Path remoteWorkDir = Files.createDirectory(temp.resolve("remote_instance"));
        remote = new TestVertxMainBuilder(remoteWorkDir)
                .withUser("anonymous", "security/users/anonymous.yaml")
                .withUser("alice", "security/users/alice.yaml")
                .withDockerRepo(
                        "remote_repo",
                        remoteWorkDir.resolve("docker_remote_data")
                )
                .build(TestDockerClient.INSECURE_PORTS[0]);

        alice = new TestDockerClient(remote.port());
        alice.start();

        final String image = alice.host() + "/remote_repo/" + IMAGE;
        alice.login("alice", "123")
                .pull(IMAGE)
                .tag(IMAGE, image)
                .push(image);

        Path proxyWorkDir = Files.createDirectory(temp.resolve("proxy_instance"));
        proxy = new TestVertxMainBuilder(proxyWorkDir)
                .withUser("bob", "security/users/bob.yaml")
                .withDockerProxyRepo(
                        "proxy_repo",
                        proxyWorkDir.resolve("docker_proxy_data"),
                        URI.create("http://localhost:" + remote.port())
                )
                .build(TestDockerClient.INSECURE_PORTS[1]);

        bob = new TestDockerClient(TestDockerClient.INSECURE_PORTS[1]);
        bob.start();
    }

    @AfterEach
    void tearDown() {
        bob.stop();
        alice.stop();
        remote.close();
        proxy.close();
    }

    @Test
    void shouldGetImageFromCache() throws Exception {
        final String proxyImage = bob.host() + "/proxy_repo/remote_repo/" + IMAGE;
        bob.login("bob", "qwerty")
                .pull(proxyImage)
                .remove(proxyImage);
        remote.close();
        bob.pull(proxyImage);
    }
}
