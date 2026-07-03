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
package com.auto1.pantera.http.resilience;

import java.util.concurrent.ForkJoinPool;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.Is;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pin contract of {@link RepoBulkheadRegistry} — small unit tests
 * that exercise the lookup / register / deregister flow that
 * {@code BaseCachedProxySlice} relies on (T-P12).
 *
 * @since 2.2.0
 */
final class RepoBulkheadRegistryTest {

    private static final String REPO_A = "repo-a-test";

    private static final String REPO_B = "repo-b-test";

    @AfterEach
    void cleanRegistry() {
        // The registry is a JVM-wide singleton; tests leak across the
        // suite if we don't deregister. The names used here are
        // intentionally unique so they can't collide with production
        // repo names.
        RepoBulkheadRegistry.instance().deregister(REPO_A);
        RepoBulkheadRegistry.instance().deregister(REPO_B);
    }

    @Test
    void emptyBeforeRegistration() {
        MatcherAssert.assertThat(
            "registry returns empty for unregistered repo",
            RepoBulkheadRegistry.instance().bulkheadFor(REPO_A).isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void registeredBulkheadIsRetrievable() {
        final RepoBulkhead bh = new RepoBulkhead(
            REPO_A, BulkheadLimits.defaults(), ForkJoinPool.commonPool()
        );
        RepoBulkheadRegistry.instance().register(REPO_A, bh);
        MatcherAssert.assertThat(
            "registered bulkhead is the same instance returned by lookup",
            RepoBulkheadRegistry.instance().bulkheadFor(REPO_A).orElseThrow(),
            new Is<>(new IsEqual<>(bh))
        );
    }

    @Test
    void reRegistrationOverwritesPreviousBulkhead() {
        final RepoBulkhead first = new RepoBulkhead(
            REPO_A, BulkheadLimits.defaults(), ForkJoinPool.commonPool()
        );
        final RepoBulkhead second = new RepoBulkhead(
            REPO_A, BulkheadLimits.defaults(), ForkJoinPool.commonPool()
        );
        RepoBulkheadRegistry.instance().register(REPO_A, first);
        RepoBulkheadRegistry.instance().register(REPO_A, second);
        MatcherAssert.assertThat(
            "the most recently registered instance wins (hot-reload semantics)",
            RepoBulkheadRegistry.instance().bulkheadFor(REPO_A).orElseThrow(),
            new Is<>(new IsEqual<>(second))
        );
    }

    @Test
    void deregisterMakesLookupEmptyAgain() {
        final RepoBulkhead bh = new RepoBulkhead(
            REPO_A, BulkheadLimits.defaults(), ForkJoinPool.commonPool()
        );
        RepoBulkheadRegistry.instance().register(REPO_A, bh);
        RepoBulkheadRegistry.instance().deregister(REPO_A);
        MatcherAssert.assertThat(
            "deregistered repo lookup returns empty",
            RepoBulkheadRegistry.instance().bulkheadFor(REPO_A).isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void distinctRepoNamesAreIsolated() {
        final RepoBulkhead bha = new RepoBulkhead(
            REPO_A, BulkheadLimits.defaults(), ForkJoinPool.commonPool()
        );
        final RepoBulkhead bhb = new RepoBulkhead(
            REPO_B, BulkheadLimits.defaults(), ForkJoinPool.commonPool()
        );
        RepoBulkheadRegistry.instance().register(REPO_A, bha);
        RepoBulkheadRegistry.instance().register(REPO_B, bhb);
        MatcherAssert.assertThat(
            "repo A returns its own bulkhead",
            RepoBulkheadRegistry.instance().bulkheadFor(REPO_A).orElseThrow(),
            new Is<>(new IsEqual<>(bha))
        );
        MatcherAssert.assertThat(
            "repo B returns its own bulkhead",
            RepoBulkheadRegistry.instance().bulkheadFor(REPO_B).orElseThrow(),
            new Is<>(new IsEqual<>(bhb))
        );
    }

    @Test
    void nullInputsAreSafe() {
        RepoBulkheadRegistry.instance().register(null, null);
        RepoBulkheadRegistry.instance().deregister(null);
        MatcherAssert.assertThat(
            "null repo name lookup returns empty without throwing",
            RepoBulkheadRegistry.instance().bulkheadFor(null).isEmpty(),
            new IsEqual<>(true)
        );
    }
}
