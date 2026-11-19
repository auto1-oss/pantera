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
package com.auto1.pantera.http.log;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link StatusReason}.
 *
 * @since 2.2.12
 */
final class StatusReasonTest {

    @ParameterizedTest
    @CsvSource({
        "400,bad_request",
        "401,authentication_required",
        "403,forbidden",
        "404,not_found",
        "405,method_not_allowed",
        "409,conflict",
        "416,range_not_satisfiable",
        "429,rate_limited",
        "418,client_error",
        "500,server_error",
        "502,bad_gateway",
        "503,service_unavailable",
        "504,gateway_timeout",
        "599,server_error"
    })
    void namesAStatusThatNothingElseExplained(final int code, final String token) {
        MatcherAssert.assertThat(new StatusReason(code).token(), new IsEqual<>(token));
    }
}
