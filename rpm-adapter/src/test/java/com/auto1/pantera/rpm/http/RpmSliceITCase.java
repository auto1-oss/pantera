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
package com.auto1.pantera.rpm.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.rpm.Digest;
import com.auto1.pantera.rpm.NamingPolicy;
import com.auto1.pantera.rpm.RepoConfig;
import com.auto1.pantera.rpm.Rpm;
import com.auto1.pantera.rpm.TestRpm;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.security.policy.PolicyByUsername;
import com.auto1.pantera.vertx.VertxSliceServer;
import com.jcabi.log.Logger;
import io.vertx.reactivex.core.Vertx;
import org.cactoos.list.ListOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.text.StringContainsInOrder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Test for {@link RpmSlice}, uses dnf and yum rpm-package managers,
 * checks that list and install works with and without authentication.
 */
@DisabledOnOs(OS.WINDOWS)
public final class RpmSliceITCase {

    /**
     * Vertx instance.
     */
    private static final Vertx VERTX = Vertx.vertx();

    /**
     * Installed packages verifier.
     */
    private static final ListOf<String> INSTALLED = new ListOf<>(
        "Installed", "time-1.7-45.el7.x86_64", "Complete!"
    );

    /**
     * Packaged list verifier.
     */
    private static final ListOf<String> AVAILABLE = new ListOf<>(
        "Available Packages", "time.x86_64", "1.7-45.el7"
    );

    /**
     * Temporary directory for all tests.
     */
    @TempDir
    Path tmp;

    /**
     * Vertx slice server instance.
     */
    private VertxSliceServer server;

    /**
     * Container.
     */
    private GenericContainer<?> cntn;

    @ParameterizedTest
    @CsvSource({
        "pantera/rpm-tests-ubi:1.0,yum,repo-pkgs",
        "pantera/rpm-tests-fedora:1.0,dnf,repository-packages"
    })
    void canListAndInstallFromPanteraRepo(final String linux,
        final String mngr, final String rey) throws Exception {
        this.start(Policy.FREE, (username, password) -> Optional.empty(), "", linux);
        MatcherAssert.assertThat(
            "Lists 'time' package",
            this.exec(mngr, rey, "list"),
            new StringContainsInOrder(RpmSliceITCase.AVAILABLE)
        );
        MatcherAssert.assertThat(
            "Installs 'time' package",
            this.exec(mngr, rey, "install"),
            new StringContainsInOrder(RpmSliceITCase.INSTALLED)
        );
    }

    @ParameterizedTest
    @CsvSource({
        "pantera/rpm-tests-ubi:1.0,yum,repo-pkgs",
        "pantera/rpm-tests-fedora:1.0,dnf,repository-packages"
    })
    void canListAndInstallFromPanteraRepoWithAuth(final String linux,
        final String mngr, final String key) throws Exception {
        final String mark = "mark";
        final String pswd = "abc";
        this.start(
            new PolicyByUsername(mark),
            new Authentication.Single(mark, pswd),
            String.format("%s:%s@", mark, pswd),
            linux
        );
        MatcherAssert.assertThat(
            "Lists 'time' package",
            this.exec(mngr, key, "list"),
            new StringContainsInOrder(RpmSliceITCase.AVAILABLE)
        );
        MatcherAssert.assertThat(
            "Installs 'time' package",
            this.exec(mngr, key, "install"),
            new StringContainsInOrder(RpmSliceITCase.INSTALLED)
        );
    }

    @AfterEach
    void stopContainer() {
        this.server.close();
        this.cntn.stop();
    }

    @AfterAll
    static void close() {
        RpmSliceITCase.VERTX.close();
    }

    /**
     * Executes yum command in container.
     * @param mngr Rpm manager
     * @param key Key to specify repo
     * @param action What to do
     * @return String stdout
     * @throws Exception On error
     */
    private String exec(final String mngr, final String key, final String action) throws Exception {
        final Container.ExecResult res = this.cntn.execInContainer(
            mngr, "-y", key, "example", action
        );
        Logger.info(this, res.toString());
        return res.getStdout();
    }

    /**
     * Starts VertxSliceServer and docker container.
     * @param policy Permissions
     * @param auth Authentication
     * @param cred String with user name and password to add in url, uname:pswd@
     * @param linux Linux distribution name and version
     * @throws Exception On error
     */
    private void start(final Policy<?> policy, final Authentication auth, final String cred,
        final String linux) throws Exception {
        final Storage storage = new InMemoryStorage();
        new TestRpm.Time().put(storage);
        final RepoConfig config = new RepoConfig.Simple(
            Digest.SHA256, new NamingPolicy.HashPrefixed(Digest.SHA1), true
        );
        new Rpm(storage, config).batchUpdate(Key.ROOT).blockingAwait();
        this.server = new VertxSliceServer(
            RpmSliceITCase.VERTX,
            new LoggingSlice(new RpmSlice(storage, policy, auth, config, Optional.empty()))
        );
        final int port = this.server.start();
        Testcontainers.exposeHostPorts(port);
        final Path setting = this.tmp.resolve("example.repo");
        this.tmp.resolve("example.repo").toFile().createNewFile();
        Files.write(
            setting,
            new ListOf<>(
                "[example]",
                "name=Example Repository",
                String.format("baseurl=http://%shost.testcontainers.internal:%d/", cred, port),
                "enabled=1",
                "gpgcheck=0"
            )
        );
        final Path product = this.tmp.resolve("product-id.conf");
        this.tmp.resolve("product-id.conf").toFile().createNewFile();
        Files.write(
            product,
            new ListOf<>(
                "[main]",
                "enabled=0"
            )
        );
        final Path mng = this.tmp.resolve("subscription-manager.conf");
        this.tmp.resolve("subscription-manager.conf").toFile().createNewFile();
        Files.write(
            mng,
            new ListOf<>(
                "[main]",
                "enabled=0"
            )
        );
        this.cntn = new GenericContainer<>(linux)
            .withCommand("tail", "-f", "/dev/null")
            .withWorkingDirectory("/home/")
            .withFileSystemBind(this.tmp.toString(), "/home");
        this.cntn.start();
        this.cntn.execInContainer("mv", "/home/example.repo", "/etc/yum.repos.d/");
        this.cntn.execInContainer("mv", "/home/product-id.conf", "/etc/yum/pluginconf.d/product-id.conf");
        this.cntn.execInContainer("mv", "/home/subscription-manager.conf", "/etc/yum/pluginconf.d/subscription-manager.conf");
    }

}
