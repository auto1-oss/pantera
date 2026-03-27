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
package com.auto1.pantera.asto.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Generic reactive cache which returns cached content by key of exist or loads from remote and
 * cache if doesn't exit.
 *
 * @since 0.24
 */
public interface Cache {

    /**
     * No cache, just load remote resource.
     */
    Cache NOP = (key, remote, ctl) -> remote.get();

    /**
     * Try to load content from cache or fallback to remote publisher if cached key doesn't exist.
     * When loading remote item, the cache may save its content to the cache storage.
     * @param key Cached item key
     * @param remote Remote source
     * @param control Cache control
     * @return Content for key
     */
    CompletionStage<Optional<? extends Content>> load(
        Key key, Remote remote, CacheControl control
    );
}
