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
package  com.auto1.pantera.conan;

import com.auto1.pantera.asto.Content;
import java.nio.charset.StandardCharsets;
import javax.json.Json;
import javax.json.JsonArray;

/**
 * Class represents revision content for Conan package.
 * @since 0.1
 */
public class RevContent {

    /**
     * Revisions json field.
     */
    private static final String REVISIONS = "revisions";

    /**
     * Revision content.
     */
    private final JsonArray content;

    /**
     * Initializes new instance.
     * @param content Array of revisions.
     */
    public RevContent(final JsonArray content) {
        this.content = content;
    }

    /**
     * Creates revisions content object for array of revisions.
     * @return Pantera Content object with revisions data.
     */
    public Content toContent() {
        return new Content.From(Json.createObjectBuilder()
            .add(RevContent.REVISIONS, this.content)
            .build().toString().getBytes(StandardCharsets.UTF_8)
        );
    }
}
