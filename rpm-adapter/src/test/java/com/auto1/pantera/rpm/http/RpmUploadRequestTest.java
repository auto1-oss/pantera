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

import com.auto1.pantera.http.rq.RequestLine;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link RpmUpload}.
 */
public final class RpmUploadRequestTest {

    @Test
    void returnsFileNameKey() {
        MatcherAssert.assertThat(
            new RpmUpload.Request(new RequestLine("PUT", "/file.rpm")).file().string(),
            new IsEqual<>("file.rpm")
        );
    }

    @ParameterizedTest
    @CsvSource({
        "/file.rpm?override=true,true",
        "/file.rpm?some_param=true&override=true,true",
        "/file.rpm?some_param=false&override=true,true",
        "/file.rpm,false",
        "/file.rpm?some_param=true,false",
        "/file.rpm?override=false,false",
        "/file.rpm?override=whatever,false",
        "/file.rpm?not_override=true,false"
    })
    void readsOverrideFlag(final String uri, final boolean expected) {
        MatcherAssert.assertThat(
            new RpmUpload.Request(new RequestLine("PUT", uri)).override(),
            new IsEqual<>(expected)
        );
    }

    @ParameterizedTest
    @CsvSource({
        "/file.rpm?skip_update=true,true",
        "/file.rpm?some_param=true&skip_update=true,true",
        "/file.rpm?some_param=false&skip_update=true,true",
        "/file.rpm,false",
        "/file.rpm?some_param=true,false",
        "/file.rpm?skip_update=false,false",
        "/file.rpm?skip_update=whatever,false",
        "/file.rpm?not_skip_update=true,false"
    })
    void readsSkipUpdateFlag(final String uri, final boolean expected) {
        MatcherAssert.assertThat(
            new RpmUpload.Request(new RequestLine("PUT", uri)).skipUpdate(),
            new IsEqual<>(expected)
        );
    }

    @ParameterizedTest
    @CsvSource({
        "/file.rpm?force=true,true",
        "/file.rpm?some_param=true&force=true,true",
        "/file.rpm?some_param=false&force=true,true",
        "/file.rpm,false",
        "/file.rpm?some_param=true,false",
        "/file.rpm?force=false,false",
        "/file.rpm?force=whatever,false",
        "/file.rpm?not_force=true,false"
    })
    void readsForceFlag(final String uri, final boolean expected) {
        MatcherAssert.assertThat(
            new RpmUpload.Request(new RequestLine("DELETE", uri)).force(),
            new IsEqual<>(expected)
        );
    }
}
