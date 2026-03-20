/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.asto;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link Layout}.
 */
public final class LayoutTest {

    @Test
    public void buildsRepositories() {
        MatcherAssert.assertThat(
            Layout.repositories().string(),
            new IsEqual<>("repositories")
        );
    }

    @Test
    public void buildsTags() {
        MatcherAssert.assertThat(
            Layout.tags("my-alpine").string(),
            new IsEqual<>("repositories/my-alpine/_manifests/tags")
        );
    }
}
