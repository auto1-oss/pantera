/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.slice;

import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import java.util.regex.Pattern;

/**
 * This slice checks that request Accept-Encoding header contains gzip value,
 * compress output body with gzip and adds {@code Content-Encoding: gzip} header.
 * <p>
 * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Encoding">Headers Docs</a>.
 */
public final class WithGzipSlice extends Slice.Wrap {

    /**
     * @param origin Slice.
     */
    public WithGzipSlice(final Slice origin) {
        super(
            new SliceRoute(
                new RtRulePath(
                    new RtRule.ByHeader("Accept-Encoding", Pattern.compile(".*gzip.*")),
                    new GzipSlice(origin)
                ),
                new RtRulePath(RtRule.FALLBACK, origin)
            )
        );
    }
}
