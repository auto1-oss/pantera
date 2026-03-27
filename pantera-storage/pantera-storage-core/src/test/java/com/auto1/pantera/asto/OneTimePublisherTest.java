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
package com.auto1.pantera.asto;

import io.reactivex.Flowable;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link OneTimePublisher}.
 * @since 0.23
 */
public final class OneTimePublisherTest {

    @Test
    public void secondAttemptLeadToFail() {
        final int one = 1;
        final Flowable<Integer> pub = Flowable.fromPublisher(
            new OneTimePublisher<>(Flowable.fromArray(one))
        );
        final Integer last = pub.lastOrError().blockingGet();
        MatcherAssert.assertThat(last, new IsEqual<>(one));
        Assertions.assertThrows(
            PanteraIOException.class,
            () -> pub.firstOrError().blockingGet()
        );
    }
}
