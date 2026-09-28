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
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasHeaders;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.pypi.meta.Metadata;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link SearchSlice}.
 */
class SearchSliceTest {

    /**
     * Test storage.
     */
    private Storage storage;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
    }

    @Test
    void returnsEmptyXmlWhenArtifactNotFound() {
        MatcherAssert.assertThat(
            new SearchSlice(this.storage),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasHeaders(
                        new Header("content-type", "text/xml"),
                        new Header("content-length", "115")
                    ),
                    new RsHasBody(SearchSlice.empty())
                ),
                new RequestLine(RqMethod.POST, "/"),
                Headers.EMPTY,
                new Content.From(this.xml("my_project").getBytes())
            )
        );
    }

    @ParameterizedTest
    @CsvSource({
        "pantera-sample-0.2.tar.gz,pantera-sample",
        "pantera-sample-2.1.tar.Z,pantera-sample",
        "pantera-sample-2.1.tar.bz2,pantera-sample",
        "pantera_sample-2.1-py3.7.egg,pantera-sample",
        "pantera_sample-0.2-py3-none-any.whl,pantera-sample",
        "alarmtime-0.1.5.tar.gz,alarmtime",
        "ABtests-0.0.2.1-py2.py3-none-any.whl,abtests"
    })
    void returnsXmlWithInfoWhenArtifactFound(final String pckg, final String name) {
        final TestResource resource = new TestResource(String.format("pypi_repo/%s", pckg));
        resource.saveTo(this.storage, new Key.From(name, pckg));
        final byte[] body = SearchSlice.found(
            new Metadata.FromArchive(resource.asInputStream(), pckg).read()
        );
        MatcherAssert.assertThat(
            new SearchSlice(this.storage),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasHeaders(
                        new Header("content-type", "text/xml"),
                        new Header("content-length", String.valueOf(body.length))
                    ),
                    new RsHasBody(body)
                ),
                new RequestLine(RqMethod.POST, "/"),
                Headers.EMPTY,
                new Content.From(this.xml(name).getBytes())
            )
        );
    }

    @Test
    void reportsTheLatestVersionByPep440NotLexically() {
        // B90: "0.9/..." sorts after "0.10/..." as a string, so search used
        // to report 0.9 as LATEST.
        new TestResource("pypi_repo/pantera-sample-0.2.tar.gz").saveTo(
            this.storage, new Key.From("pantera-sample", "0.10", "pantera-sample-0.2.tar.gz")
        );
        new TestResource("pypi_repo/pantera-sample-2.1.tar.bz2").saveTo(
            this.storage, new Key.From("pantera-sample", "0.9", "pantera-sample-2.1.tar.bz2")
        );
        final String body = new SearchSlice(this.storage).response(
            new RequestLine(RqMethod.POST, "/"),
            Headers.EMPTY,
            new Content.From(this.xml("pantera-sample").getBytes())
        ).join().body().asString();
        MatcherAssert.assertThat(
            "metadata must come from the 0.10 release directory",
            body,
            Matchers.containsString("<string>0.2</string>")
        );
    }

    @Test
    void escapesXmlInFoundValues() {
        final String xml = new String(
            SearchSlice.found(
                new com.auto1.pantera.pypi.meta.PackageInfo.FromMetadata(
                    "Name: a&b\nVersion: 1.0\nSummary: x < y & <b>z</b>\n"
                )
            ),
            java.nio.charset.StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            xml,
            Matchers.containsString("<string>x &lt; y &amp; &lt;b&gt;z&lt;/b&gt;</string>")
        );
    }

    @Test
    void faultSliceAnswersSearchWithXmlRpcFault() {
        // B90: groups/proxies answered pip search with an empty 405 and pip
        // crashed with an AssertionError; an XML-RPC fault is what pip
        // (and pypi.org, which disabled search) expects.
        final com.auto1.pantera.http.Response response = new SearchFaultSlice(
            (line, headers, body) -> java.util.concurrent.CompletableFuture.completedFuture(
                com.auto1.pantera.http.ResponseBuilder.methodNotAllowed().build()
            )
        ).response(
            new RequestLine(RqMethod.POST, "/"),
            Headers.from(new Header("content-type", "text/xml")),
            new Content.From(this.xml("anything").getBytes())
        ).join();
        MatcherAssert.assertThat(
            "fault is a 200 XML-RPC response",
            response.status(),
            new org.hamcrest.core.IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "body is an XML-RPC fault",
            response.body().asString(),
            Matchers.containsString("<fault>")
        );
    }

    @Test
    void faultSlicePassesOtherRequestsThrough() {
        final com.auto1.pantera.http.Response response = new SearchFaultSlice(
            (line, headers, body) -> java.util.concurrent.CompletableFuture.completedFuture(
                com.auto1.pantera.http.ResponseBuilder.noContent().build()
            )
        ).response(
            new RequestLine(RqMethod.GET, "/simple/"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            response.status(),
            new org.hamcrest.core.IsEqual<>(RsStatus.NO_CONTENT)
        );
    }

    private String xml(final String name) {
        return String.join(
            "\n", "<?xml version='1.0'?>",
            "<methodCall>",
            "<methodName>search</methodName>",
            "<params>",
            "<param>",
            "<value><struct>",
            "<member>",
            "<name>name</name>",
            "<value><array><data>",
            String.format("<value><string>%s</string></value>", name),
            "</data></array></value>",
            "</member>",
            "<member>",
            "<name>summary</name>",
            "<value><array><data>",
            "<value><string>abcdef</string></value>",
            "</data></array></value>",
            "</member>",
            "</struct></value>",
            "</param>",
            "<param>",
            "<value><string>or</string></value>",
            "</param>",
            "</params>",
            "</methodCall>"
        );
    }

}
