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
package com.auto1.pantera.goproxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Remaining;
import io.reactivex.Single;
import java.nio.ByteBuffer;
import java.time.Instant;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Unit test for Goproxy class.
 *
 * @since 0.3
 */
public class GoproxyTest {
    @Test
    public void generatesVersionedJson() {
        final Instant timestamp = Instant.parse("2020-03-17T08:05:12.32496732Z");
        final Single<Content> content = Goproxy.generateVersionedJson(
            "0.0.1", timestamp
        );
        final ByteBuffer data = content.flatMap(Goproxy::readCompletely).blockingGet();
        MatcherAssert.assertThat(
            "Content does not match",
            "{\"Version\":\"v0.0.1\",\"Time\":\"2020-03-17T08:05:12Z\"}",
            Matchers.equalTo(new String(new Remaining(data).bytes()))
        );
    }
}
