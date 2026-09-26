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
package com.auto1.pantera.files;

import com.auto1.pantera.asto.Key;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for the Files-repository listing XSS: a writer
 * could upload a blob whose KEY carries markup, and the HTML directory
 * listing interpolated that key raw into both the {@code href} and the link
 * text. The rendered page must escape every untrusted key.
 *
 * @since 2.2.9
 */
final class BlobListFormatXssTest {

    @Test
    void markupInAKeyIsEscapedInTheHtmlListing() {
        final String html = BlobListFormat.Standard.HTML.apply(
            List.of(new Key.From("dir/<script>alert(1)</script>.txt"))
        );
        MatcherAssert.assertThat(
            "a script tag in a key must not survive into the listing markup",
            html.contains("<script>"), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the key must be rendered entity-escaped",
            html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), new IsEqual<>(true)
        );
    }

    @Test
    void quoteInAKeyCannotBreakOutOfTheHrefAttribute() {
        final String html = BlobListFormat.Standard.HTML.apply(
            List.of(new Key.From("a\" onmouseover=\"alert(1)"))
        );
        MatcherAssert.assertThat(
            "a double quote in a key must not close the href attribute",
            html.contains("onmouseover=\"alert"), new IsEqual<>(false)
        );
    }
}
