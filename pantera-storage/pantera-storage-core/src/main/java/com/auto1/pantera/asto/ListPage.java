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

import java.util.List;
import java.util.Optional;

/**
 * One page of a key listing, see
 * {@link Storage#listPage(Key, Optional, int)}.
 *
 * @param keys Keys of the page, in listing order
 * @param next Token to pass as {@code after} for the following page; empty
 *  when the listing is finished
 * @since 2.2.14
 */
public record ListPage(List<Key> keys, Optional<String> next) {
}
