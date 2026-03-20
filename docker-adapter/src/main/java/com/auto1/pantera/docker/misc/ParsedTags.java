/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.misc;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Tags;

import javax.json.JsonString;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Parsed {@link Tags} that is capable of extracting tags list and repository name
 * from origin {@link Tags}.
 */
public final class ParsedTags implements Tags {

    /**
     * Origin tags.
     */
    private final Tags origin;

    /**
     * Ctor.
     *
     * @param origin Origin tags.
     */
    public ParsedTags(final Tags origin) {
        this.origin = origin;
    }

    @Override
    public Content json() {
        return this.origin.json();
    }

    /**
     * Get repository name from origin.
     *
     * @return Repository name.
     */
    public CompletionStage<String> repo() {
        return origin.json()
            .asJsonObjectFuture()
            .thenApply(root -> ImageRepositoryName.validate(root.getString("name")));
    }

    /**
     * Get tags list from origin.
     *
     * @return Tags list.
     */
    public CompletionStage<List<String>> tags() {
        return origin.json()
            .asJsonObjectFuture()
            .thenApply(root -> root.getJsonArray("tags")
                .getValuesAs(JsonString.class)
                .stream()
                .map(val -> ImageTag.validate(val.getString()))
                .toList());
    }

}
