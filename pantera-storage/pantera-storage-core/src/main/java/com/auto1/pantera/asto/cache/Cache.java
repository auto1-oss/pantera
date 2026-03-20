/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
