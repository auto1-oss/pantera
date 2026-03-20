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
        "artipie-sample-0.2.tar.gz,artipie-sample",
        "artipie-sample-2.1.tar.Z,artipie-sample",
        "artipie-sample-2.1.tar.bz2,artipie-sample",
        "artipie_sample-2.1-py3.7.egg,artipie-sample",
        "artipie_sample-0.2-py3-none-any.whl,artipie-sample",
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
