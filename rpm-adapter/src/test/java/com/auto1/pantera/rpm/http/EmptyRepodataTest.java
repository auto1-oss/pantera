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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.rpm.RepoConfig;
import com.auto1.pantera.rpm.TestRpm;
import com.auto1.pantera.security.policy.Policy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * dnf must be able to use a repository before the first package is
 * uploaded: an empty repository serves valid, empty repodata.
 *
 * @since 2.2.9
 */
final class EmptyRepodataTest {

    /**
     * Storage.
     */
    private Storage asto;

    /**
     * Slice.
     */
    private RpmSlice slice;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
        this.slice = new RpmSlice(
            this.asto, Policy.FREE,
            (name, pass) -> Optional.of(new AuthUser(name, "test")),
            new RepoConfig.Simple(),
            Optional.empty()
        );
    }

    @Test
    void servesRepomdOfEmptyRepository() {
        final Response rsp = this.get("/repodata/repomd.xml");
        MatcherAssert.assertThat("status", rsp.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat("repomd", rsp.body().asString(), new StringContains("<repomd"));
    }

    @Test
    void servesThePrimaryIndexRepomdReferences() {
        final Matcher href = Pattern.compile("href=\"(repodata/[^\"]*primary[^\"]*)\"")
            .matcher(this.get("/repodata/repomd.xml").body().asString());
        MatcherAssert.assertThat("primary referenced", href.find(), new IsEqual<>(true));
        MatcherAssert.assertThat(
            "primary served",
            this.get("/" + href.group(1)).status(),
            new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    void uploadAfterEmptyMetadataIsListed() throws Exception {
        this.get("/repodata/repomd.xml");
        MatcherAssert.assertThat(
            "upload accepted",
            this.slice.response(
                new RequestLine(RqMethod.PUT, "/abc-1.01-26.git20200127.fc32.ppc64le.rpm"),
                Headers.from(new Authorization.Basic("alice", "pw")),
                new Content.From(Files.readAllBytes(new TestRpm.Abc().path()))
            ).join().status(),
            new IsEqual<>(RsStatus.ACCEPTED)
        );
        final Matcher href = Pattern.compile("href=\"(repodata/[^\"]*primary[^\"]*)\"")
            .matcher(this.get("/repodata/repomd.xml").body().asString());
        href.find();
        MatcherAssert.assertThat(
            "package listed in primary",
            new String(
                new GZIPInputStream(
                    new ByteArrayInputStream(this.get("/" + href.group(1)).body().asBytes())
                ).readAllBytes(),
                StandardCharsets.UTF_8
            ),
            new StringContains("<name>abc</name>")
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
