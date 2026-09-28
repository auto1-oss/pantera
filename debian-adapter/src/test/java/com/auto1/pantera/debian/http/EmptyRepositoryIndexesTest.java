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
package com.auto1.pantera.debian.http;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.debian.AstoGzArchive;
import com.auto1.pantera.debian.Config;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.policy.Policy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * apt must be able to update from a Debian repository before any package
 * for an architecture or component is uploaded: the Release file lists
 * every configured architecture and component, so their Packages indexes
 * must exist.
 *
 * @since 2.2.9
 */
final class EmptyRepositoryIndexesTest {

    /**
     * Packages index of amd64 in main.
     */
    private static final String AMD64 = "dists/my_repo/main/binary-amd64/Packages.gz";

    /**
     * Storage.
     */
    private Storage asto;

    /**
     * Slice.
     */
    private DebianSlice slice;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
        this.slice = new DebianSlice(
            this.asto, Policy.FREE,
            (name, pass) -> Optional.of(new AuthUser(name, "test")),
            new Config.FromYaml(
                "my_repo",
                Yaml.createYamlMappingBuilder()
                    .add("Architectures", "amd64 arm64")
                    .add("Components", "main contrib").build(),
                new InMemoryStorage()
            ),
            Optional.empty()
        );
    }

    @Test
    void servesEmptyPackagesIndexOfEmptyRepository() throws IOException {
        final Response rsp = this.get("/" + EmptyRepositoryIndexesTest.AMD64);
        MatcherAssert.assertThat("status", rsp.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat(
            "empty index",
            new GZIPInputStream(new ByteArrayInputStream(rsp.body().asBytes()))
                .readAllBytes().length,
            new IsEqual<>(0)
        );
    }

    @Test
    void releaseListsEveryConfiguredIndex() {
        final String release = this.get("/dists/my_repo/Release").body().asString();
        MatcherAssert.assertThat(
            "amd64 main", release, new StringContains("main/binary-amd64/Packages.gz")
        );
        MatcherAssert.assertThat(
            "arm64 contrib", release, new StringContains("contrib/binary-arm64/Packages.gz")
        );
    }

    @Test
    void createsIndexMissingFromExistingRepository() {
        this.asto.save(
            new Key.From("dists/my_repo/Release"), new Content.From("Codename: my_repo\n".getBytes())
        ).join();
        MatcherAssert.assertThat(
            "status",
            this.get("/" + EmptyRepositoryIndexesTest.AMD64).status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Release lists the new index",
            this.asto.value(new Key.From("dists/my_repo/Release")).join().asString(),
            new StringContains("main/binary-amd64/Packages.gz")
        );
    }

    @Test
    void uploadAfterEmptyIndexAddsThePackage() throws IOException {
        this.get("/dists/my_repo/InRelease");
        MatcherAssert.assertThat(
            "upload ok",
            this.slice.response(
                new RequestLine(RqMethod.PUT, "/pool/main/aglfn_1.7-3_amd64.deb"),
                Headers.from(new Authorization.Basic("alice", "pw")),
                new Content.From(new TestResource("aglfn_1.7-3_amd64.deb").asBytes())
            ).join().status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "package listed",
            new AstoGzArchive(this.asto).unpack(new Key.From(EmptyRepositoryIndexesTest.AMD64)),
            new StringContains("Package: aglfn")
        );
    }

    private Response get(final String path) {
        return this.slice.response(
            new RequestLine(RqMethod.GET, path),
            Headers.from(new Authorization.Basic("alice", "pw")),
            Content.EMPTY
        ).join();
    }
}
