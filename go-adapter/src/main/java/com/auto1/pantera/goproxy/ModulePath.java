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
package com.auto1.pantera.goproxy;

import java.util.Locale;

/**
 * A Go module path as it appears in GOPROXY URLs and storage keys, where
 * every upper-case letter is escaped as {@code !} followed by its lower-case
 * form ({@code github.com/!burnt!sushi/toml} for
 * {@code github.com/BurntSushi/toml}).
 *
 * <p>The artifact index records the real module path, so search finds a
 * mixed-case module by the name developers use; the escaped form stays in
 * storage keys and URLs.</p>
 *
 * @since 2.2.9
 */
public final class ModulePath {

    /**
     * Escaped path.
     */
    private final String escaped;

    /**
     * Ctor.
     * @param escaped Escaped module path
     */
    public ModulePath(final String escaped) {
        this.escaped = escaped;
    }

    /**
     * The real module path: {@code !x} decoded to {@code X}. A dangling or
     * non-letter escape is kept as is.
     * @return Decoded module path
     */
    public String decoded() {
        if (this.escaped.indexOf('!') < 0) {
            return this.escaped;
        }
        final StringBuilder out = new StringBuilder(this.escaped.length());
        int idx = 0;
        while (idx < this.escaped.length()) {
            final char chr = this.escaped.charAt(idx);
            if (chr == '!' && idx + 1 < this.escaped.length()
                && Character.isLowerCase(this.escaped.charAt(idx + 1))) {
                out.append(
                    String.valueOf(this.escaped.charAt(idx + 1)).toUpperCase(Locale.ROOT)
                );
                idx += 2;
            } else {
                out.append(chr);
                idx += 1;
            }
        }
        return out.toString();
    }
}
