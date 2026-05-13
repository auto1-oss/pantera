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
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;

/**
 * Reactive streams-tck verification suit for {@link OneTimePublisher}.
 * @since 0.23
 */
public final class OneTimePublisherVerificationTest extends PublisherVerification<Integer> {

    /**
     * Ctor.
     */
    public OneTimePublisherVerificationTest() {
        super(new TestEnvironment());
    }

    @Override
    public Publisher<Integer> createPublisher(final long elements) {
        return Flowable.empty();
    }

    @Override
    public Publisher<Integer> createFailedPublisher() {
        final OneTimePublisher<Integer> publisher = new OneTimePublisher<>(Flowable.fromArray(1));
        Flowable.fromPublisher(publisher).toList().blockingGet();
        return publisher;
    }

    @Override
    public long maxElementsFromPublisher() {
        return 0;
    }
}
