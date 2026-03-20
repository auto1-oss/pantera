/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.IsJson;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.misc.PanteraProperties;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import wtf.g4s8.hamcrest.json.JsonContains;
import wtf.g4s8.hamcrest.json.JsonHas;
import wtf.g4s8.hamcrest.json.JsonValueIs;

/**
 * Tests for {@link VersionSlice}.
 * @since 0.21
 */
final class VersionSliceTest {
    @Test
    void returnVersionOfApplication() {
        final PanteraProperties proprts = new PanteraProperties();
        MatcherAssert.assertThat(
            new VersionSlice(proprts),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasBody(
                        new IsJson(
                            new JsonContains(
                                new JsonHas("version", new JsonValueIs(proprts.version()))
                            )
                        )
                    )
                ),
                new RequestLine(RqMethod.GET, "/.version")
            )
        );
    }
}
