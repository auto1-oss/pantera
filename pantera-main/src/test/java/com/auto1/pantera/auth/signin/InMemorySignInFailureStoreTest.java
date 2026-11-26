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
package com.auto1.pantera.auth.signin;

import java.util.ArrayList;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link InMemorySignInFailureStore}.
 *
 * @since 2.2.15
 */
final class InMemorySignInFailureStoreTest extends SignInFailureStoreContract {

    @Override
    SignInFailureStore store() {
        return new InMemorySignInFailureStore();
    }

    @Test
    void oldestLinesMakeRoomWhenTheStoreIsFull() {
        final SignInFailureStore store = this.store();
        final List<SignInFailure> batch = new ArrayList<>(1000);
        for (int idx = 0; idx < InMemorySignInFailureStore.MAX_LINES + 10; idx += 1) {
            batch.add(
                new SignInFailure(
                    SignInFailure.PASSWORD, "user-" + idx, "192.0.2.7", "", "wrong_credentials",
                    true, "", "ui", 1L, 0L, SignInFailureStoreContract.START.plusSeconds(idx),
                    SignInFailureStoreContract.START.plusSeconds(idx), 0, null, ""
                )
            );
        }
        store.save(batch);
        final SignInFailureStore.Page page = store.list(
            new SignInFailureStore.Query(SignInFailure.PASSWORD, "", false, "", 1, 0)
        );
        MatcherAssert.assertThat(
            "no more lines than the bound",
            page.total(), new IsEqual<>(InMemorySignInFailureStore.MAX_LINES)
        );
        MatcherAssert.assertThat(
            "and the most recent one is among them",
            page.items().get(0).username(),
            new IsEqual<>("user-" + (InMemorySignInFailureStore.MAX_LINES + 9))
        );
    }
}
