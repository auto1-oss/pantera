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
package com.auto1.pantera.helm.http;

import com.auto1.pantera.helm.ChartYaml;

/**
 * A push of a chart version that is already stored in an immutable repository.
 * @since 2.2.10
 */
final class ChartVersionExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Ctor.
     * @param chart Chart.yaml of the refused archive
     */
    ChartVersionExistsException(final ChartYaml chart) {
        super(
            String.format(
                "Chart %s version %s already exists and the repository is immutable",
                chart.name(), chart.version()
            )
        );
    }
}
