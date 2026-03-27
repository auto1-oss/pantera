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
package com.auto1.pantera.http;

import com.auto1.pantera.micrometer.MicrometerSlice;
import com.auto1.pantera.settings.MetricsContext;

/**
 * Slice is base for any slice served by Pantera.
 * It is designed to gather request & response metrics, perform logging, handle errors at top level.
 * With all that functionality provided request are forwarded to origin slice
 * and response is given back to caller.
 *
 * @since 0.11
 */
public final class BaseSlice extends Slice.Wrap {

    /**
     * Ctor.
     *
     * @param mctx Metrics context.
     * @param origin Origin slice.
     */
    public BaseSlice(final MetricsContext mctx, final Slice origin) {
        super(
            BaseSlice.wrapToBaseMetricsSlices(
                mctx,
                new SafeSlice(origin)
            )
        );
    }

    /**
     * Wraps slice to metric related slices when {@code Metrics} is defined.
     *
     * @param mctx Metrics context.
     * @param origin Original slice.
     * @return Wrapped slice.
     */
    private static Slice wrapToBaseMetricsSlices(final MetricsContext mctx, final Slice origin) {
        Slice res = origin;
        if (mctx.http()) {
            res = new MicrometerSlice(origin);
        }
        return res;
    }
}
