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
package com.auto1.pantera.asto;

import java.io.Serializable;
import java.util.Comparator;

/**
 * Orders strings by their UTF-8 bytes, which is the order an S3 listing
 * returns keys in. It equals {@link String#compareTo(String)} except where a
 * character outside the Basic Multilingual Plane meets one in
 * {@code U+E000..U+FFFF}: UTF-16 sorts the surrogate pair first, UTF-8 last.
 *
 * @since 2.2.14
 */
public final class Utf8Order implements Comparator<String>, Serializable {

    private static final long serialVersionUID = 7715003218034482301L;

    /**
     * First surrogate code unit.
     */
    private static final char SURROGATES = '\uD800';

    /**
     * First code unit above the surrogates.
     */
    private static final char PRIVATE = '';

    @Override
    public int compare(final String left, final String right) {
        final int common = Math.min(left.length(), right.length());
        int res = 0;
        for (int idx = 0; idx < common && res == 0; idx += 1) {
            final char one = left.charAt(idx);
            final char two = right.charAt(idx);
            if (one != two) {
                res = Utf8Order.rank(one) - Utf8Order.rank(two);
            }
        }
        if (res == 0) {
            res = left.length() - right.length();
        }
        return res;
    }

    /**
     * Position of a UTF-16 code unit in code point order: the surrogates
     * are moved above {@code U+E000..U+FFFF}.
     * @param unit Code unit
     * @return Rank
     */
    private static int rank(final char unit) {
        final int rank;
        if (unit < Utf8Order.SURROGATES) {
            rank = unit;
        } else if (unit < Utf8Order.PRIVATE) {
            rank = unit + 0x2000;
        } else {
            rank = unit - 0x800;
        }
        return rank;
    }
}
