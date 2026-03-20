/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.asto;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.docker.Tags;
import com.auto1.pantera.docker.misc.Pagination;

import javax.json.Json;
import java.util.Collection;

/**
 * Asto implementation of {@link Tags}. Tags created from list of keys.
 *
 * @since 0.8
 */
final class AstoTags implements Tags {

    /**
     * Repository name.
     */
    private final String name;

    /**
     * Tags root key.
     */
    private final Key root;

    /**
     * List of keys inside tags root.
     */
    private final Collection<Key> keys;

    private final Pagination pagination;

    /**
     * @param name Image repository name.
     * @param root Tags root key.
     * @param keys List of keys inside tags root.
     * @param pagination Pagination parameters.
     */
    AstoTags(String name, Key root, Collection<Key> keys, Pagination pagination) {
        this.name = name;
        this.root = root;
        this.keys = keys;
        this.pagination = pagination;
    }

    @Override
    public Content json() {
        return new Content.From(
            Json.createObjectBuilder()
                .add("name", this.name)
                .add("tags", pagination.apply(new Children(root, keys).names().stream()))
                .build()
                .toString()
                .getBytes()
        );
    }
}
