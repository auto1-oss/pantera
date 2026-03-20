/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.proxy.json;

import com.auto1.pantera.asto.test.TestResource;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Cached package content test.
 *
 * @since 0.1
 */
public class CachedContentTest {
    @Test
    public void getsValue() {
        final String original = new String(
            new TestResource("json/original.json").asBytes()
        );
        final JsonObject json = new CachedContent(original, "asdas").value();
        MatcherAssert.assertThat(
            json.getJsonObject("versions").getJsonObject("1.0.0")
                .getJsonObject("dist").getString("tarball"),
            new IsEqual<>("/asdas/-/asdas-1.0.0.tgz")
        );
    }
}
