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
package com.auto1.pantera.npm;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.npm.http.NpmSlice;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.vertx.VertxSliceServer;
import com.jcabi.log.Logger;
import io.vertx.reactivex.core.Vertx;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.LinkedList;

/**
 * IT for npm dist-tags command.
 *
 * <p>Split-brain regression guard (WS4-npm.3, spec &sect;6): every scenario
 * publishes through a real {@code npm publish} — no hand-planted
 * {@code meta.json} — proving {@code npm dist-tag ls/add/rm} and
 * {@code npm publish --tag} work purely off the per-version layout's durable
 * {@code .dist-tags.json} sidecar.</p>
 */
@DisabledOnOs(OS.WINDOWS)
public final class NpmDistTagsIT {

    /**
     * Test package name (matches the {@code simple-npm-project} fixture).
     */
    private static final String PKG = "@hello/simple-npm-project";

    @TempDir
    Path tmp;

    /**
     * Vert.x used to create tested FileStorage.
     */
    private Vertx vertx;

    /**
     * Server.
     */
    private VertxSliceServer server;

    /**
     * Repository URL.
     */
    private String url;

    /**
     * Container.
     */
    private GenericContainer<?> cntn;

    /**
     * Repository storage (server-side).
     */
    private Storage repo;

    /**
     * Client-side project files storage, bind-mounted into the container.
     */
    private Storage data;

    @BeforeEach
    void setUp() throws Exception {
        this.repo = new InMemoryStorage();
        this.data = new FileStorage(this.tmp);
        this.vertx = Vertx.vertx();
        final int port = new RandomFreePort().value();
        this.url = String.format("http://host.testcontainers.internal:%d", port);
        this.server = new VertxSliceServer(
            this.vertx,
            new LoggingSlice(new NpmSlice(
                URI.create(this.url).toURL(), this.repo, (Policy<?>) Policy.FREE,
                new Authentication.Single("testuser", "testpassword"),
                (TokenAuthentication) tkn -> java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty()),
                "*", java.util.Optional.of(new LinkedList<>())
            )),
            port
        );
        this.server.start();
        Testcontainers.exposeHostPorts(port);
        Files.writeString(
            this.tmp.resolve(".npmrc"),
            String.format("//host.testcontainers.internal:%d/:_auth=dGVzdHVzZXI6dGVzdHBhc3N3b3Jk", port),
            StandardCharsets.UTF_8
        );
        this.cntn = new GenericContainer<>("node:14-alpine")
            .withCommand("tail", "-f", "/dev/null")
            .withWorkingDirectory("/home/")
            .withFileSystemBind(this.tmp.toString(), "/home");
        this.cntn.start();
    }

    @AfterEach
    void tearDown() {
        this.server.stop();
        this.vertx.close();
        this.cntn.stop();
    }

    @Test
    void lsDistTagsWorksAfterRealPublish() throws Exception {
        this.publish("tmp/pkg", "1.0.1");
        MatcherAssert.assertThat(
            "Hosted publish never hand-writes meta.json",
            this.repo.exists(new Key.From(NpmDistTagsIT.PKG, "meta.json")).join(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            this.exec("npm", "dist-tag", "ls", NpmDistTagsIT.PKG, "--registry", this.url),
            new StringContains("latest: 1.0.1")
        );
    }

    @Test
    void addDistTagsWorksAfterRealPublish() throws Exception {
        this.publish("tmp/pkg", "1.0.1");
        final String tag = "beta";
        MatcherAssert.assertThat(
            "npm dist-tag add succeeds",
            this.exec(
                "npm", "dist-tag", "add",
                String.format("%s@1.0.1", NpmDistTagsIT.PKG), tag, "--registry", this.url
            ),
            new StringContains(String.format("+%s: %s@1.0.1", tag, NpmDistTagsIT.PKG))
        );
        MatcherAssert.assertThat(
            "New tag is visible in a subsequent ls, alongside the untouched latest",
            this.exec("npm", "dist-tag", "ls", NpmDistTagsIT.PKG, "--registry", this.url),
            new StringContains(String.format("%s: 1.0.1", tag))
        );
    }

    @Test
    void rmDistTagsWorksAfterRealPublish() throws Exception {
        this.publish("tmp/pkg", "1.0.1");
        final String tag = "beta";
        this.exec(
            "npm", "dist-tag", "add",
            String.format("%s@1.0.1", NpmDistTagsIT.PKG), tag, "--registry", this.url
        );
        MatcherAssert.assertThat(
            "npm dist-tag rm succeeds",
            this.exec("npm", "dist-tag", "rm", NpmDistTagsIT.PKG, tag, "--registry", this.url),
            new StringContains(String.format("-%s: %s@1.0.1", tag, NpmDistTagsIT.PKG))
        );
        MatcherAssert.assertThat(
            "Removed tag is no longer listed",
            this.exec("npm", "dist-tag", "ls", NpmDistTagsIT.PKG, "--registry", this.url),
            new IsNot<>(new StringContains(tag + ":"))
        );
    }

    @Test
    void publishWithCustomTagLeavesLatestUntouched() throws Exception {
        this.publish("tmp/pkg", "1.0.1");
        this.publish("tmp/pkg-next", "1.0.2", "--tag", "next");
        final String tags = this.exec(
            "npm", "dist-tag", "ls", NpmDistTagsIT.PKG, "--registry", this.url
        );
        MatcherAssert.assertThat(
            "latest is untouched by the --tag next publish (real npm semantics: "
                + "--tag only ever sets the tag it names)",
            tags,
            new StringContains("latest: 1.0.1")
        );
        MatcherAssert.assertThat(
            "next reflects the version published under --tag next",
            tags,
            new StringContains("next: 1.0.2")
        );
    }

    /**
     * Publish the {@code simple-npm-project} fixture at a given version,
     * rewriting its {@code package.json} in place (no {@code npm version}
     * dependency on npm-CLI-version-specific subcommand support).
     *
     * @param path Container-relative path to stage the project under
     * @param version Version to publish
     * @param extra Extra {@code npm publish} arguments (e.g. {@code --tag next})
     * @throws Exception On container exec failure
     */
    private void publish(final String path, final String version, final String... extra)
        throws Exception {
        new TestResource("simple-npm-project").addFilesTo(this.data, new Key.From(path));
        final Key pkgJson = new Key.From(path, "package.json");
        final String rewritten = this.data.value(pkgJson).join().asString()
            .replace("\"version\": \"1.0.1\"", String.format("\"version\": \"%s\"", version));
        this.data.save(pkgJson, new Content.From(rewritten.getBytes(StandardCharsets.UTF_8))).join();
        final java.util.List<String> args = new java.util.ArrayList<>();
        args.add("npm");
        args.add("publish");
        args.add(path);
        args.add("--registry");
        args.add(this.url);
        args.addAll(java.util.Arrays.asList(extra));
        MatcherAssert.assertThat(
            "npm publish succeeds",
            this.exec(args.toArray(new String[0])),
            new StringContains(String.format("+ %s@%s", NpmDistTagsIT.PKG, version))
        );
    }

    private String exec(final String... command) throws Exception {
        final Container.ExecResult res = this.cntn.execInContainer(command);
        Logger.debug(this, "Command:\n%s\nResult:\n%s", String.join(" ", command), res.toString());
        return res.getStdout();
    }
}
