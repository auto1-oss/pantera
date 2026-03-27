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
package com.auto1.pantera.gem.http;

import com.auto1.pantera.gem.GemMeta.MetaInfo;
import com.auto1.pantera.gem.JsonMetaFormat;
import com.auto1.pantera.gem.YamlMetaFormat;
import com.auto1.pantera.http.PanteraHttpException;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;

import javax.json.Json;
import javax.json.JsonObjectBuilder;
import java.util.function.Function;

/**
 * Gem meta response format.
 */
enum MetaResponseFormat implements Function<MetaInfo, Response> {
    /**
     * JSON response format.
     */
    JSON {
        @Override
        public Response apply(final MetaInfo meta) {
            final JsonObjectBuilder json = Json.createObjectBuilder();
            meta.print(new JsonMetaFormat(json));
            return ResponseBuilder.ok().jsonBody(json.build())
                .build();
        }
    },

    /**
     * Yaml response format.
     */
    YAML {
        @Override
        public Response apply(final MetaInfo meta) {
            final YamlMetaFormat.Yamler yamler = new YamlMetaFormat.Yamler();
            meta.print(new YamlMetaFormat(yamler));
            return ResponseBuilder.ok()
                .yamlBody(yamler.build().toString())
                .build();
        }
    };

    /**
     * Format by name.
     * @param name Format name
     * @return Response format
     */
    static MetaResponseFormat byName(final String name) {
        return switch (name) {
            case "json" -> MetaResponseFormat.JSON;
            case "yaml" -> MetaResponseFormat.YAML;
            default -> throw new PanteraHttpException(
                RsStatus.BAD_REQUEST, String.format("unsupported format type `%s`", name)
            );
        };
    }
}
