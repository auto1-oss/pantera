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
package com.auto1.pantera.api.v1.admin;

import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * {@link SliceRepoFetch}: a repository that cannot be built fails its own
 * fetch instead of the caller.
 *
 * @since 2.2.9
 */
final class SliceRepoFetchTest {

    @Test
    void aRepositoryThatCannotBeBuiltFailsTheFutureNotTheCaller() {
        final CompletableFuture<RepoFetch.Fetched> fetch = new SliceRepoFetch(
            repo -> {
                throw new IllegalStateException("`password` is not specified for remote");
            }
        ).get("php-satis", "/p2/openai-php/client.json", null, null, 1024);
        MatcherAssert.assertThat(fetch.isCompletedExceptionally(), new IsEqual<>(true));
    }
}
