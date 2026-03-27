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
package com.auto1.pantera.helm.http;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.helm.ChartYaml;
import com.auto1.pantera.helm.metadata.IndexYamlMapping;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.google.common.base.Throwables;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test case for {@link DownloadIndexSlice}.
 */
final class DownloadIndexSliceTest {

    private Storage storage;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://central.pantera.com/", "http://central.pantera.com"})
    void returnsOkAndUpdateEntriesUrlsForBaseWithOrWithoutTrailingSlash(final String base) {
        new TestResource("index.yaml").saveTo(this.storage);

        Response resp = new DownloadIndexSlice(base, this.storage)
            .response(new RequestLine(RqMethod.GET, "/index.yaml"),
                Headers.EMPTY, Content.EMPTY)
            .join();
        ResponseAssert.checkOk(resp);
        MatcherAssert.assertThat(
            "Uri was corrected modified",
            new ChartYaml(
                new IndexYamlMapping(resp.body().asString())
                    .byChart("tomcat").get(0)
            ).urls().get(0),
            new IsEqual<>(String.format("%s/tomcat-0.4.1.tgz", base.replaceAll("/$", "")))
        );
    }

    @Test
    void returnsBadRequest() {
        MatcherAssert.assertThat(
            new DownloadIndexSlice("http://localhost:8080", this.storage),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.BAD_REQUEST),
                new RequestLine(RqMethod.GET, "/bad/request")
            )
        );
    }

    @Test
    void returnsNotFound() {
        MatcherAssert.assertThat(
            new DownloadIndexSlice("http://localhost:8080", this.storage),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.GET, "/index.yaml")
            )
        );
    }

    @Test
    void throwsMalformedUrlExceptionForInvalidBase() {
        final String base = "withoutschemelocalhost:8080";
        final Throwable thr = Assertions.assertThrows(
            PanteraException.class,
            () -> new DownloadIndexSlice(base, this.storage)
        );
        MatcherAssert.assertThat(
            Throwables.getRootCause(thr),
            new IsInstanceOf(MalformedURLException.class)
        );
    }

    @Test
    void throwsExceptionForInvalidUriFromIndexYaml() {
        final String base = "http://localhost:8080";
        final AtomicReference<Throwable> exc = new AtomicReference<>();
        new TestResource("index/invalid_uri.yaml")
            .saveTo(this.storage, new Key.From("index.yaml"));
        new DownloadIndexSlice(base, this.storage)
            .response(
                new RequestLine(RqMethod.GET, "/index.yaml"),
                Headers.EMPTY,
                Content.EMPTY
            ).handle(
                (res, thr) -> {
                    exc.set(thr);
                    return CompletableFuture.allOf();
                }
            ).join();
        MatcherAssert.assertThat(
            Throwables.getRootCause(exc.get()),
            new IsInstanceOf(URISyntaxException.class)
        );
    }
}
