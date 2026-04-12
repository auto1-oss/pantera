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
package com.auto1.pantera.vuln.backend;

import com.auto1.pantera.vuln.ScannerBackend;
import com.auto1.pantera.vuln.VulnerabilitySettings;

/**
 * Factory that selects the correct {@link ScannerBackend} based on
 * the {@code scanner_type} field in {@link VulnerabilitySettings}.
 *
 * <p>Supported types:
 * <ul>
 *   <li>{@code trivy} — {@link TrivyScannerBackend}</li>
 *   <li>{@code grype} — {@link GrypeScannerBackend}</li>
 * </ul>
 *
 * @since 2.2.0
 */
public final class ScannerBackendFactory {

    /**
     * Private ctor — utility class, not instantiable.
     */
    private ScannerBackendFactory() {
    }

    /**
     * Create the appropriate {@link ScannerBackend} for the given settings.
     * @param settings Vulnerability settings containing {@code scanner_type} and
     *                 {@code scanner_path}
     * @return Matching backend implementation
     * @throws IllegalArgumentException if {@code scanner_type} is not recognised
     */
    public static ScannerBackend create(final VulnerabilitySettings settings) {
        return switch (settings.scannerType()) {
            case "trivy" -> new TrivyScannerBackend(settings.scannerPath());
            case "grype" -> new GrypeScannerBackend(settings.scannerPath());
            default -> throw new IllegalArgumentException(
                String.format(
                    "Unknown scanner_type '%s'. Supported values: trivy, grype",
                    settings.scannerType()
                )
            );
        };
    }
}
