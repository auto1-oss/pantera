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
package com.auto1.pantera.hex.http;

import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link UserSlice}.
 * @since 0.2
 */
class UserSliceTest {

    /**
     * User slice.
     */
    private Slice userslice;

    @BeforeEach
    void init() {
        this.userslice = new UserSlice();
    }

    @Test
    void responseNoContent() {
        MatcherAssert.assertThat(
            this.userslice,
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NO_CONTENT),
                new RequestLine(RqMethod.GET, "/users/pantera")
            )
        );
    }
}
