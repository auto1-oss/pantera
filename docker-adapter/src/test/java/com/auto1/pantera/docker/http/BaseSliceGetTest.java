/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DockerSlice}.
 * Base GET endpoint.
 */
class BaseSliceGetTest {

    private DockerSlice slice;

    @BeforeEach
    void setUp() {
        this.slice = TestDockerAuth.slice(new AstoDocker("test_registry", new InMemoryStorage()));
    }

    @Test
    void shouldRespondOkToVersionCheck() {
        final Response response = this.slice
            .response(new RequestLine(RqMethod.GET, "/v2/"), TestDockerAuth.headers(), Content.EMPTY)
            .join();
        ResponseAssert.check(response, RsStatus.OK,
            new Header("Docker-Distribution-API-Version", "registry/2.0"));
    }
}
