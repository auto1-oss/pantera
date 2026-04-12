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
package com.auto1.pantera.vuln;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Low-level CVE scanner backend contract.
 *
 * <p>Implementations wrap specific scanning tools (Trivy, Grype, OSV-Scanner, etc.)
 * and are responsible for invoking the tool against a prepared directory and
 * parsing the tool-specific output into {@link VulnerabilityFinding} objects.
 *
 * <p>Backend instances are called from a virtual-thread executor — they may
 * perform blocking I/O (spawning subprocesses, reading stdout) without blocking
 * the Vert.x event loop.
 *
 * @since 2.2.0
 */
public interface ScannerBackend {

    /**
     * Short identifier for this scanner, written into scan reports.
     * Examples: {@code "trivy"}, {@code "grype"}, {@code "osv"}.
     *
     * @return Scanner name
     */
    String name();

    /**
     * Run the scanner against a directory containing dependency manifests and
     * return parsed findings.
     *
     * <p>Implementations MUST:
     * <ul>
     *   <li>Only read files from {@code scanDir}.</li>
     *   <li>Honour the {@code timeoutSeconds} limit; return empty list on timeout.</li>
     *   <li>Never throw for scanner-level errors — log and return empty list.</li>
     * </ul>
     *
     * @param scanDir Directory containing one or more dependency manifest files
     * @param timeoutSeconds Maximum seconds to allow the scanner to run
     * @return Parsed vulnerability findings (never null; may be empty)
     * @throws IOException If subprocess I/O fails at the OS level
     * @throws InterruptedException If the calling thread is interrupted
     */
    List<VulnerabilityFinding> scan(Path scanDir, int timeoutSeconds)
        throws IOException, InterruptedException;
}
