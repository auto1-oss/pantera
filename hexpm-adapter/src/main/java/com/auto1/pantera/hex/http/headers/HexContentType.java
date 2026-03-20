/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */

package com.auto1.pantera.hex.http.headers;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Accept;
import com.auto1.pantera.http.headers.ContentType;

import java.util.Map;

/**
 * ContentType header for HexPm.
 */
public class HexContentType {
    /**
     * Default ContentType.
     */
    static final String DEFAULT_TYPE = "application/vnd.hex+erlang";

    /**
     * Request headers.
     */
    private final Headers headers;

    /**
     * @param headers Request headers.
     */
    public HexContentType(Headers headers) {
        this.headers = headers;
    }

    /**
     * Fill ContentType header for response.
     *
     * @return Filled headers.
     */
    public Headers fill() {
        String type = HexContentType.DEFAULT_TYPE;
        for (final Map.Entry<String, String> header : this.headers) {
            if (Accept.NAME.equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) {
                type = header.getValue();
            }
        }
        return this.headers.copy().add(ContentType.mime(type));
    }
}
