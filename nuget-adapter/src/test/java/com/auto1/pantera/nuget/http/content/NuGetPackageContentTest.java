/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.nuget.http.content;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.nuget.AstoRepository;
import com.auto1.pantera.nuget.http.NuGet;
import com.auto1.pantera.nuget.http.TestAuthentication;
import com.auto1.pantera.security.policy.PolicyByUsername;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

/**
 * Tests for {@link NuGet}.
 * Package Content resource.
 */
class NuGetPackageContentTest {

    private Storage storage;

    /**
     * Tested NuGet slice.
     */
    private NuGet nuget;

    @BeforeEach
    void init() throws Exception {
        this.storage = new InMemoryStorage();
        this.nuget = new NuGet(
            URI.create("http://localhost").toURL(),
            new AstoRepository(this.storage),
            new PolicyByUsername(TestAuthentication.USERNAME),
            new TestAuthentication(),
            "test",
            Optional.empty()
        );
    }

    @Test
    void shouldGetPackageContent() {
        final byte[] data = "data".getBytes();
        new BlockingStorage(this.storage).save(
            new Key.From("package", "1.0.0", "content.nupkg"),
            data
        );
        Response response = this.nuget.response(
            new RequestLine(
                RqMethod.GET,
                "/content/package/1.0.0/content.nupkg"
            ), TestAuthentication.HEADERS, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.OK, response.status());
        Assertions.assertArrayEquals(data, response.body().asBytes());
    }

    @Test
    void shouldFailGetPackageContentWhenNotExists() {
        Response response = this.nuget.response(
            new RequestLine(
                RqMethod.GET,
                "/content/package/1.0.0/logo.png"
            ), TestAuthentication.HEADERS, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.NOT_FOUND, response.status());
    }

    @Test
    void shouldFailPutPackageContent() {
        final Response response = this.nuget.response(
            new RequestLine(
                RqMethod.PUT,
                "/content/package/1.0.0/content.nupkg"
            ), TestAuthentication.HEADERS, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.METHOD_NOT_ALLOWED, response.status());
    }

    @Test
    void shouldGetPackageVersions() {
        final byte[] data = "example".getBytes();
        new BlockingStorage(this.storage).save(
            new Key.From("package2", "index.json"),
            data
        );
        final Response response = this.nuget.response(
            new RequestLine(
                RqMethod.GET,
                "/content/package2/index.json"
            ), TestAuthentication.HEADERS, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.OK, response.status());
        Assertions.assertArrayEquals(data, response.body().asBytes());
    }

    @Test
    void shouldFailGetPackageVersionsWhenNotExists() {
        final Response response = this.nuget.response(
            new RequestLine(
                RqMethod.GET,
                "/content/unknown-package/index.json"
            ), TestAuthentication.HEADERS, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.NOT_FOUND, response.status());
    }

    @Test
    void shouldUnauthorizedGetPackageContentByAnonymousUser() {
        final Response response = this.nuget.response(
            new RequestLine(
                RqMethod.GET,
                "/content/package/2.0.0/content.nupkg"
            ), Headers.EMPTY, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.UNAUTHORIZED, response.status());
        Assertions.assertTrue(
            response.headers().stream()
                .anyMatch(header ->
                    header.getKey().equalsIgnoreCase("WWW-Authenticate")
                        && header.getValue().contains("Basic realm=\"artipie\"")
                )
        );
    }
}
