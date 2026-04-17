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
/**
 * PyPI cooldown metadata filtering implementation.
 *
 * <p>This package provides PyPI-specific implementations of the cooldown metadata SPI:</p>
 * <ul>
 *   <li>{@link com.auto1.pantera.pypi.cooldown.PypiMetadataParser} -
 *       Parses PyPI Simple Index HTML (PEP 503) into link records</li>
 *   <li>{@link com.auto1.pantera.pypi.cooldown.PypiMetadataFilter} -
 *       Filters blocked versions from the link list</li>
 *   <li>{@link com.auto1.pantera.pypi.cooldown.PypiMetadataRewriter} -
 *       Serializes filtered links back to HTML</li>
 *   <li>{@link com.auto1.pantera.pypi.cooldown.PypiMetadataRequestDetector} -
 *       Detects {@code /simple/{package}/} metadata endpoints</li>
 *   <li>{@link com.auto1.pantera.pypi.cooldown.PypiSimpleIndex} -
 *       Parsed representation (record) of the Simple Index page</li>
 *   <li>{@link com.auto1.pantera.pypi.cooldown.PypiCooldownResponseFactory} -
 *       Builds 403 responses for blocked PyPI packages</li>
 * </ul>
 *
 * <p>PyPI Simple Index HTML structure (PEP 503):</p>
 * <pre>
 * &lt;!DOCTYPE html&gt;&lt;html&gt;&lt;body&gt;
 * &lt;a href="../../packages/my-pkg-1.0.0.tar.gz#sha256=abc"&gt;my-pkg-1.0.0.tar.gz&lt;/a&gt;
 * &lt;a href="../../packages/my-pkg-1.1.0-py3-none-any.whl#sha256=def"
 *    data-requires-python="&amp;gt;=3.8"&gt;my-pkg-1.1.0-py3-none-any.whl&lt;/a&gt;
 * &lt;/body&gt;&lt;/html&gt;
 * </pre>
 *
 * <p>When filtering blocked versions:</p>
 * <ol>
 *   <li>Version is extracted from each link's filename (sdist or wheel naming)</li>
 *   <li>{@code <a>} elements for blocked versions are removed</li>
 *   <li>Links with unparseable versions are preserved (safety)</li>
 *   <li>Remaining links are serialized back to valid PEP 503 HTML</li>
 * </ol>
 *
 * @since 2.2.0
 */
package com.auto1.pantera.pypi.cooldown;
