/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.files.FileProxySlice;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import com.auto1.pantera.http.group.GroupSlice;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;

import java.net.URI;
import java.net.URISyntaxException;
import org.apache.http.client.utils.URIBuilder;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration tests for grouped repositories.
 * @since 0.10
 * @todo #370:30min Enable `test.networkEnabled` property for some CI builds.
 *  Make sure these tests are not failing due to network issues, maybe we should retry
 *  it to avoid false failures.
 */
@EnabledIfSystemProperty(named = "test.networkEnabled", matches = "true|yes|on|1")
final class GroupRepositoryITCase {

    /**
     * Http clients for proxy slice.
     */
    private final JettyClientSlices clients = new JettyClientSlices();

    @BeforeEach
    void setUp() throws Exception {
        this.clients.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        this.clients.stop();
    }

    @Test
    void fetchesCorrectContentFromGroupedFilesProxy() throws Exception {
        MatcherAssert.assertThat(
            new GroupSlice(
                this.proxy("/pantera/none-2/"),
                this.proxy("/pantera/tests/"),
                this.proxy("/pantera/none-1/")
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(
                    RqMethod.GET, URI.create("/GroupRepositoryITCase-one.txt").toString()
                )
            )
        );
    }

    private Slice proxy(final String path) throws URISyntaxException {
        return new FileProxySlice(
            this.clients,
            new URIBuilder(URI.create("https://central.pantera.com"))
                .setPath(path)
                .build()
        );
    }
}
