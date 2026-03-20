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
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.stream.JsonParser;
import java.io.StringReader;

/**
 * Tests for RevContent class.
 */
class RevContentTest {

    /**
     * Revisions json field.
     */
    private static final String REVISIONS = "revisions";

    @Test
    public void emptyContent() {
        final JsonArrayBuilder builder = Json.createArrayBuilder();
        final RevContent revc = new RevContent(builder.build());
        final Content content = revc.toContent();
        final JsonParser parser = Json.createParser(new StringReader(content.asString()));
        parser.next();
        final JsonArray revs = parser.getObject().getJsonArray(RevContentTest.REVISIONS);
        MatcherAssert.assertThat("The json array must be empty", revs.isEmpty());
    }

    @Test
    public void contentGeneration() {
        final JsonArrayBuilder builder = Json.createArrayBuilder();
        final int testval = 1;
        builder.add(testval);
        final RevContent revc = new RevContent(builder.build());
        final Content content = revc.toContent();
        final JsonParser parser = Json.createParser(new StringReader(content.asString()));
        parser.next();
        final JsonArray revs = parser.getObject().getJsonArray(RevContentTest.REVISIONS);
        MatcherAssert.assertThat(
            "The size of the json array is incorrect",
            revs.size() == 1
        );
        MatcherAssert.assertThat(
            "The json array data has incorrect value",
            revs.get(0).toString().equals(Integer.toString(testval))
        );
    }
}
