/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.misc;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Tags;

import javax.json.Json;
import java.util.List;

/**
 * {@link Tags} that is a page of given tags list.
 */
public final class TagsPage implements Tags {

    private final String repoName;

    private final List<String> tags;

    private final Pagination pagination;

    /**
     * @param repoName Repository name.
     * @param tags Tags.
     * @param pagination Pagination parameters.
     */
    public TagsPage(String repoName, List<String> tags, Pagination pagination) {
        this.repoName = repoName;
        this.tags = tags;
        this.pagination = pagination;
    }

    @Override
    public Content json() {
        return new Content.From(
            Json.createObjectBuilder()
                .add("name", this.repoName)
                .add("tags", pagination.apply(tags.stream()))
                .build()
                .toString()
                .getBytes()
        );
    }
}
