/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;

public record Response(RsStatus status, Headers headers, Content body) {

    @Override
    public String toString() {
        return "Response{" +
            "status=" + status +
            ", headers=" + headers +
            ", hasBody=" + body.size().map(s -> s > 0).orElse(false) +
            '}';
    }
}
