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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.security.policy.Policy;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

/**
 * Test for {@link PySlice}.
 */
class PySliceTest {

    /**
     * Test slice.
     */
    private PySlice slice;

    /**
     * Test storage.
     */
    private Storage storage;

    /**
     * Authorization headers for requests.
     */
    private Headers authorization;

    private static final String USER = "pypi-user";
    private static final String PASSWORD = "secret";

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
        this.slice = new PySlice(
            this.storage, Policy.FREE,
            new com.auto1.pantera.http.auth.Authentication.Single(USER, PASSWORD),
            "*", Optional.empty()
        );
        this.authorization = Headers.from(new Authorization.Basic(USER, PASSWORD));
    }

    @Test
    void returnsIndexPage() {
        final byte[] content = "python package".getBytes();
        final String key = "simple/simple-0.1-py3-cp33m-linux_x86.whl";
        this.storage.save(new Key.From(key), new Content.From(content)).join();
        Response resp = this.slice.response(
            new RequestLine("GET", "/simple"),
            this.authorization,
            Content.EMPTY
        ).join();
        ResponseAssert.check(resp, RsStatus.OK,
            new Header("Content-type", "text/html; charset=utf-8"),
            new Header("Content-Length", "216")
        );
        MatcherAssert.assertThat(
            resp.body().asString(),
            new StringContains("simple-0.1-py3-cp33m-linux_x86.whl")
        );
    }

    @Test
    void returnsIndexPageByRootRequest() {
        final byte[] content = "python package".getBytes();
        final String key = "simple/alarmtime-0.1.5.tar.gz";
        this.storage.save(new Key.From(key), new Content.From(content)).join();
        Response resp = this.slice.response(
            new RequestLine("GET", "/"),
            this.authorization,
            Content.EMPTY
        ).join();
        ResponseAssert.check(resp, RsStatus.OK,
            new Header("Content-type", "text/html; charset=utf-8"),
            new Header("Content-Length", "192")
        );
        MatcherAssert.assertThat(
            resp.body().asString(),
            new StringContains("alarmtime-0.1.5.tar.gz")
        );
    }

    @Test
    void redirectsToNormalizedPath() {
        ResponseAssert.check(
            this.slice.response(
                new RequestLine("GET", "/one/Two_three"),
                this.authorization,
                Content.EMPTY
            ).join(),
            RsStatus.MOVED_PERMANENTLY,
            new Header("Location", "/one/two-three")
        );
    }

    @Test
    void returnsBadRequestOnEmptyPost() {
        ResponseAssert.check(
            this.slice.response(
                new RequestLine("POST", "/sample.tar"),
                Headers.from(
                    new Authorization.Basic(USER, PASSWORD),
                    new Header("content-type", "multipart/form-data; boundary=\"abc123\"")
                ),
                Content.EMPTY
            ).join(),
            RsStatus.BAD_REQUEST
        );
    }

    @ParameterizedTest
    @CsvSource({
        "python zip package,my/zip/my-project.zip",
        "python tar package,my-tar/my-project.tar",
        "python package,my/my-project.tar.gz",
        "python tar z package,my/my-project-z.tar.Z",
        "python tar bz2 package,new/my/my-project.tar.bz2",
        "python wheel,my/my-project.whl",
        "python egg package,eggs/python-egg.egg"
    })
    void downloadsVariousArchives(final String content, final String key) {
        this.storage.save(new Key.From(key), new Content.From(content.getBytes())).join();
        ResponseAssert.check(
            this.slice.response(
                new RequestLine("GET", key),
                this.authorization,
                Content.EMPTY
            ).join(),
            RsStatus.OK,
            content.getBytes()
        );
    }

    @Test
    void getServesPep658MetadataFile() {
        final String key = "my/1.0.0/my-1.0.0-py3-none-any.whl.metadata";
        final byte[] content = "Metadata-Version: 2.1\nName: my\nVersion: 1.0.0\n".getBytes();
        this.storage.save(new Key.From(key), new Content.From(content)).join();
        ResponseAssert.check(
            this.slice.response(
                new RequestLine("GET", "/" + key),
                this.authorization,
                Content.EMPTY
            ).join(),
            RsStatus.OK,
            content
        );
    }

    @Test
    void headServesPep658MetadataFileWithNoBody() {
        final String key = "my/1.0.0/my-1.0.0-py3-none-any.whl.metadata";
        final byte[] content = "Metadata-Version: 2.1\nName: my\nVersion: 1.0.0\n".getBytes();
        this.storage.save(new Key.From(key), new Content.From(content)).join();
        final Response resp = this.slice.response(
            new RequestLine("HEAD", "/" + key),
            this.authorization,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(resp.status(), new org.hamcrest.core.IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat(
            "HEAD response body must be empty",
            resp.body().asBytesFuture().join().length,
            new org.hamcrest.core.IsEqual<>(0)
        );
    }

    @Test
    void legacyJsonReturns404ForUnknownPackage() {
        ResponseAssert.check(
            this.slice.response(
                new RequestLine("GET", "/pypi/nonexistent/json"),
                this.authorization,
                Content.EMPTY
            ).join(),
            RsStatus.NOT_FOUND
        );
    }

    @Test
    void legacyJsonResolvesPackageWithVersionsAndFiles() {
        this.storage.save(
            new Key.From("mypkg", "1.0.0", "mypkg-1.0.0.tar.gz"),
            new Content.From("v1".getBytes())
        ).join();
        this.storage.save(
            new Key.From("mypkg", "2.0.0", "mypkg-2.0.0.tar.gz"),
            new Content.From("v2".getBytes())
        ).join();
        final Response resp = this.slice.response(
            new RequestLine("GET", "/pypi/mypkg/json"),
            this.authorization,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(resp.status(), new org.hamcrest.core.IsEqual<>(RsStatus.OK));
        final String body = resp.body().asString();
        final javax.json.JsonObject json = javax.json.Json
            .createReader(new java.io.StringReader(body)).readObject();
        MatcherAssert.assertThat(
            "info.version must be the PEP-440-highest version",
            json.getJsonObject("info").getString("version"),
            new org.hamcrest.core.IsEqual<>("2.0.0")
        );
        MatcherAssert.assertThat(
            "releases must list every version",
            json.getJsonObject("releases").containsKey("1.0.0")
                && json.getJsonObject("releases").containsKey("2.0.0")
        );
        MatcherAssert.assertThat(
            "urls must describe the latest version's file",
            json.getJsonArray("urls").getJsonObject(0).getString("filename"),
            new org.hamcrest.core.IsEqual<>("mypkg-2.0.0.tar.gz")
        );
    }

}
