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
package com.auto1.pantera.auth.failedlogins;

import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link InMemoryFailedLoginStore}.
 */
final class InMemoryFailedLoginStoreTest extends FailedLoginStoreContract {

    @Override
    FailedLoginStore store() {
        return new InMemoryFailedLoginStore();
    }

    @Test
    void storeTellsItsOwnTime() {
        final AtomicReference<Instant> clock =
            new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        final FailedLoginStore store = new InMemoryFailedLoginStore(clock::get);
        store.fail(
            "alice", FailedLoginsConfig.defaults(),
            new LoginOrigin("192.0.2.7", "curl", "repository", "password")
        );
        clock.set(Instant.parse("2026-01-01T00:00:10Z"));
        final FailedLoginStore.Seen seen = store.read("alice");
        MatcherAssert.assertThat(
            "the failure carries the time it happened",
            seen.state().lastFailure(), new IsEqual<>(Instant.parse("2026-01-01T00:00:00Z"))
        );
        MatcherAssert.assertThat(
            "a read carries the time of the read",
            seen.now(), new IsEqual<>(Instant.parse("2026-01-01T00:00:10Z"))
        );
    }
}
