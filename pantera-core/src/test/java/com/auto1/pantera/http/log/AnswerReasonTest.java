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

import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import java.util.List;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AnswerReason}.
 *
 * @since 2.2.12
 */
final class AnswerReasonTest {

    @Test
    void readsTheReasonAnAnswerCarries() {
        final Response stamped = new AnswerReason(ResponseBuilder.notFound().build())
            .stamped("negative_cache");
        MatcherAssert.assertThat(
            new AnswerReason(stamped).token(), new IsEqual<>(Optional.of("negative_cache"))
        );
    }

    @Test
    void anAnswerWithoutAReasonHasNone() {
        MatcherAssert.assertThat(
            new AnswerReason(ResponseBuilder.notFound().build()).token(),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void stampingReplacesAnEarlierReason() {
        final Response first = new AnswerReason(ResponseBuilder.notFound().build())
            .stamped("upstream_status_404");
        final Response second = new AnswerReason(first).stamped("negative_cache");
        MatcherAssert.assertThat(
            second.headers().values(AnswerReason.HEADER),
            new IsEqual<>(List.of("negative_cache"))
        );
    }

    @Test
    void strippingRemovesTheHeaderAndKeepsTheOthers() {
        final Response stamped = new AnswerReason(
            ResponseBuilder.notFound().header("X-Other", "kept").build()
        ).stamped("negative_cache");
        final Response stripped = new AnswerReason(stamped).stripped();
        MatcherAssert.assertThat(
            "the reason does not leave Pantera",
            stripped.headers().values(AnswerReason.HEADER), new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat(
            "other headers stay",
            stripped.headers().values("X-Other"), new IsEqual<>(List.of("kept"))
        );
    }

    @Test
    void aValueThatIsNotATokenIsNotAReason() {
        // An upstream could send the header on an answer a proxy relays: free
        // text must not reach the access log as a reason.
        final Response forged = ResponseBuilder.notFound()
            .header(AnswerReason.HEADER, "all good\", \"user.name\": \"admin").build();
        MatcherAssert.assertThat(
            new AnswerReason(forged).token(), new IsEqual<>(Optional.empty())
        );
    }
}
