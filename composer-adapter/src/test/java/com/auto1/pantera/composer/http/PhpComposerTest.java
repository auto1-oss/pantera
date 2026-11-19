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
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.composer.AllPackages;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.security.policy.Policy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link PhpComposer}.
 */
class PhpComposerTest {

    /**
     * Request line to get all packages.
     */
    private static final RequestLine GET_PACKAGES = new RequestLine(
        RqMethod.GET, "/packages.json"
    );

    /**
     * Storage used in tests.
     */
    private Storage storage;

    /**
     * Tested PhpComposer slice.
     */
    private PhpComposer php;

    /**
     * Authorization headers for requests.
     */
    private Headers authorization;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
        final String user = "composer-user";
        final String password = "secret";
        this.php = new PhpComposer(
            new AstoRepository(this.storage),
            Policy.FREE,
            new com.auto1.pantera.http.auth.Authentication.Single(user, password),
            "*",
            Optional.empty()
        );
        this.authorization = Headers.from(new Authorization.Basic(user, password));
    }

    @Test
    void shouldGetPackageContent() throws Exception {
        final byte[] data = "data".getBytes();
        new BlockingStorage(this.storage).save(
            new Key.From("p2", "vendor", "package.json"),
            data
        );
        final Response response = this.php.response(
            new RequestLine(RqMethod.GET, "/p2/vendor/package.json"),
            this.authorization,
            Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.OK, response.status());
        Assertions.assertArrayEquals(data, response.body().asBytes());
    }

    @ParameterizedTest
    @CsvSource({
        "p2/vendor/package.json, /p2/vendor/package.json",
        "artifacts/vendor/package/1.0.0/vendor-package-1.0.0.zip, /artifacts/vendor/package/1.0.0/vendor-package-1.0.0.zip",
        "vendor-package-1.0.0.zip, /vendor-package-1.0.0.zip"
    })
    void shouldAnswerHeadLikeGetWithoutBody(final String key, final String path)
        throws Exception {
        // curl -I, a CI installer validating a download URL: HEAD of a file
        // that exists must not answer 404.
        final byte[] data = "stored bytes".getBytes();
        new BlockingStorage(this.storage).save(new Key.From(key), data);
        final Response get = this.php.response(
            new RequestLine(RqMethod.GET, path), this.authorization, Content.EMPTY
        ).join();
        final Response head = this.php.response(
            new RequestLine(RqMethod.HEAD, path), this.authorization, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.OK, get.status(), "GET serves the file");
        Assertions.assertEquals(RsStatus.OK, head.status(), "HEAD answers like GET");
        Assertions.assertEquals(
            get.headers().values("Content-Length"), head.headers().values("Content-Length"),
            "HEAD announces the length of the GET body"
        );
        Assertions.assertEquals(0, head.body().asBytes().length, "HEAD has no body");
        get.body().asBytes();
    }

    @Test
    void shouldAnswerHeadOfRootIndex() {
        Assertions.assertEquals(
            RsStatus.OK,
            this.php.response(
                new RequestLine(RqMethod.HEAD, "/packages.json"), this.authorization, Content.EMPTY
            ).join().status()
        );
    }

    @Test
    void shouldAnswerHeadOfMissingFileNotFound() {
        Assertions.assertEquals(
            RsStatus.NOT_FOUND,
            this.php.response(
                new RequestLine(RqMethod.HEAD, "/artifacts/vendor/none/1.0.0/none-1.0.0.zip"),
                this.authorization, Content.EMPTY
            ).join().status()
        );
    }

    @Test
    void shouldFailGetPackageMetadataWhenNotExists() {
        final Response response = this.php.response(
            new RequestLine(RqMethod.GET, "/p/vendor/unknown-package.json"),
            this.authorization,
            Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.NOT_FOUND, response.status());
    }

    @Test
    void shouldGetAllPackages() throws Exception {
        final byte[] data = "all packages".getBytes();
        new BlockingStorage(this.storage).save(new AllPackages(), data);
        final Response response = this.php.response(
            PhpComposerTest.GET_PACKAGES,
            this.authorization,
            Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.OK, response.status());
        // With Satis layout, response is always the Satis index (not "all packages")
        final String body = response.body().asString();
        Assertions.assertTrue(
            body.contains("metadata-url") || body.equals("all packages"),
            "Should return Satis index or saved packages.json"
        );
    }

    @Test
    void shouldFailGetAllPackagesWhenNotExists() {
        final Response response = this.php.response(
            PhpComposerTest.GET_PACKAGES,
            this.authorization,
            Content.EMPTY
        ).join();
        // With Satis layout, GET /packages.json always returns OK (Satis index)
        Assertions.assertEquals(RsStatus.OK, response.status());
        // Verify it's the Satis index
        final String body = response.body().asString();
        Assertions.assertTrue(
            body.contains("metadata-url"),
            "Should return Satis index even when no packages exist"
        );
    }

    @Test
    void shouldPutRoot() {
        final Response response = this.php.response(
            new RequestLine(RqMethod.PUT, "/"),
            this.authorization,
            new Content.From(
                new TestResource("minimal-package.json").asBytes()
            )
        ).join();
        Assertions.assertEquals(RsStatus.CREATED, response.status());
    }
}
