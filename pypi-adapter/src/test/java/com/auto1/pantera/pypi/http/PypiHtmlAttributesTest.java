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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.pypi.meta.PypiSidecar;
import java.time.Instant;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for the PyPI simple-index HTML injection: the
 * yank REASON is caller-controlled (any authenticated user could yank) and
 * was emitted verbatim into the {@code data-yanked} attribute of every
 * later index render, so a reason containing a quote could close the
 * attribute and inject markup (a malicious package link) for all readers.
 *
 * @since 2.2.9
 */
final class PypiHtmlAttributesTest {

    @Test
    void yankReasonCannotBreakOutOfItsAttribute() {
        final PypiSidecar.Meta meta = new PypiSidecar.Meta(
            null, Instant.EPOCH, true,
            Optional.of("\"><a href=\"https://evil.example/pkg.whl\">x</a>"),
            Optional.empty()
        );
        final String attrs = PypiHtmlAttributes.of(meta);
        MatcherAssert.assertThat(
            "a yank reason must not be able to close the data-yanked attribute",
            attrs.contains("\"><a "), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the reason must be rendered entity-escaped",
            attrs.contains("data-yanked=\"&quot;&gt;&lt;a href=&quot;"), new IsEqual<>(true)
        );
    }

    @Test
    void requiresPythonIsFullyEscapedNotJustAngleBrackets() {
        final PypiSidecar.Meta meta = new PypiSidecar.Meta(
            "\" onmouseover=\"alert(1)", Instant.EPOCH, false, Optional.empty(), Optional.empty()
        );
        MatcherAssert.assertThat(
            "a quote in requires-python must not close its attribute",
            PypiHtmlAttributes.of(meta).contains("onmouseover=\"alert"), new IsEqual<>(false)
        );
    }
}
