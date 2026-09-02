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

import com.auto1.pantera.http.html.HtmlEscape;
import com.auto1.pantera.pypi.meta.PypiSidecar;

/**
 * The one place PEP 503 {@code data-*} attributes are rendered from sidecar
 * metadata — shared by the hosted index ({@code SliceIndex}) and the
 * regenerated index ({@code IndexGenerator}).
 *
 * <p>Every value is untrusted at render time: {@code requires-python} comes
 * from the uploaded metadata, the yank reason from whichever authenticated
 * caller yanked the release. Before 2.2.9 the reason was emitted verbatim
 * and {@code requires-python} escaped only angle brackets, so a quote could
 * close the attribute and inject markup into every later index render.</p>
 *
 * @since 2.2.9
 */
final class PypiHtmlAttributes {

    /**
     * Not instantiable.
     */
    private PypiHtmlAttributes() {
    }

    /**
     * Render the attribute string for one file link.
     *
     * @param meta Sidecar metadata
     * @return Attribute string (may be empty); every value entity-escaped
     */
    static String of(final PypiSidecar.Meta meta) {
        final StringBuilder attrs = new StringBuilder();
        if (meta.requiresPython() != null && !meta.requiresPython().isEmpty()) {
            attrs.append(String.format(
                " data-requires-python=\"%s\"", HtmlEscape.escape(meta.requiresPython())
            ));
        }
        if (meta.yanked()) {
            attrs.append(String.format(
                " data-yanked=\"%s\"", HtmlEscape.escape(meta.yankedReason().orElse(""))
            ));
        }
        if (meta.distInfoMetadata().isPresent()) {
            attrs.append(String.format(
                " data-dist-info-metadata=\"sha256=%s\"",
                HtmlEscape.escape(meta.distInfoMetadata().get())
            ));
        }
        return attrs.toString();
    }
}
