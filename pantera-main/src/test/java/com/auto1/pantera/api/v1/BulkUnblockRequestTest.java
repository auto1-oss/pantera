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
package com.auto1.pantera.api.v1;

import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BulkUnblockRequest} parsing and validation.
 */
final class BulkUnblockRequestTest {

    @Test
    void parsesItems() {
        final List<BulkUnblockRequest.Item> items = new BulkUnblockRequest(
            "{\"items\":[{\"repo\":\"npm-proxy\",\"artifact\":\"lodash\",\"version\":\"4.17.21\"}]}"
        ).items();
        MatcherAssert.assertThat(
            items,
            new IsEqual<>(List.of(new BulkUnblockRequest.Item("npm-proxy", "lodash", "4.17.21")))
        );
    }

    @Test
    void duplicateItemsAreCollapsed() {
        final List<BulkUnblockRequest.Item> items = new BulkUnblockRequest(
            "{\"items\":[{\"repo\":\"r\",\"artifact\":\"a\",\"version\":\"1\"},"
                + "{\"repo\":\"r\",\"artifact\":\"a\",\"version\":\"1\"}]}"
        ).items();
        MatcherAssert.assertThat(items.size(), new IsEqual<>(1));
    }

    @Test
    void trimsWhitespaceAroundValues() {
        final List<BulkUnblockRequest.Item> items = new BulkUnblockRequest(
            "{\"items\":[{\"repo\":\" r \",\"artifact\":\" a\",\"version\":\"1 \"}]}"
        ).items();
        MatcherAssert.assertThat(
            items.get(0), new IsEqual<>(new BulkUnblockRequest.Item("r", "a", "1"))
        );
    }

    @Test
    void rejectsEmptyMissingMalformedAndOversized() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new BulkUnblockRequest("{\"items\":[]}").items(), "empty"
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new BulkUnblockRequest("{}").items(), "missing"
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new BulkUnblockRequest("not json").items(), "malformed"
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new BulkUnblockRequest(null).items(), "null body"
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new BulkUnblockRequest("{\"items\":[{\"repo\":\"r\",\"version\":\"1\"}]}").items(),
            "blank artifact"
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new BulkUnblockRequest("{\"items\":[1]}").items(), "non-object item"
        );
        final StringBuilder many = new StringBuilder("{\"items\":[");
        for (int idx = 0; idx <= BulkUnblockRequest.MAX_ITEMS; idx = idx + 1) {
            if (idx > 0) {
                many.append(',');
            }
            many.append("{\"repo\":\"r\",\"artifact\":\"a").append(idx)
                .append("\",\"version\":\"1\"}");
        }
        many.append("]}");
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new BulkUnblockRequest(many.toString()).items(), "501 items"
        );
    }
}
