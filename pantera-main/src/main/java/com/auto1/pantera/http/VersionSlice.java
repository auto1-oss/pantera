/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.misc.PanteraProperties;

import javax.json.Json;
import java.util.concurrent.CompletableFuture;

/**
 * Returns JSON with information about version of application.
 */
public final class VersionSlice implements Slice {

    private final PanteraProperties properties;

    public VersionSlice(final PanteraProperties properties) {
        this.properties = properties;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        return ResponseBuilder.ok()
            .jsonBody(Json.createArrayBuilder()
                .add(Json.createObjectBuilder().add("version", this.properties.version()))
                .build()
            ).completedFuture();
    }
}
