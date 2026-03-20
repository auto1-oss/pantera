/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.PanteraException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * PackagePath tests.
 * @since 0.1
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class PackagePathTest {
    @Test
    public void getsPath() {
        final PackagePath path = new PackagePath("npm-proxy");
        MatcherAssert.assertThat(
            path.value("/npm-proxy/@vue/vue-cli"),
            new IsEqual<>("@vue/vue-cli")
        );
    }

    @Test
    public void getsPathWithRootContext() {
        final PackagePath path = new PackagePath("");
        MatcherAssert.assertThat(
            path.value("/@vue/vue-cli"),
            new IsEqual<>("@vue/vue-cli")
        );
    }

    @Test
    public void failsByPattern() {
        final PackagePath path = new PackagePath("npm-proxy");
        Assertions.assertThrows(
            PanteraException.class,
            () -> path.value("/npm-proxy/@vue/vue-cli/-/fake")
        );
    }

    @Test
    public void failsByPrefix() {
        final PackagePath path = new PackagePath("npm-proxy");
        Assertions.assertThrows(
            PanteraException.class,
            () -> path.value("/@vue/vue-cli")
        );
    }
}

