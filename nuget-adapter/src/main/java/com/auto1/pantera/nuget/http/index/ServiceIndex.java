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
package com.auto1.pantera.nuget.http.index;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.nuget.http.Absent;
import com.auto1.pantera.nuget.http.Resource;
import com.auto1.pantera.nuget.http.Route;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Service index route.
 * See <a href="https://docs.microsoft.com/en-us/nuget/api/service-index">Service Index</a>
 */
public final class ServiceIndex implements Route {

    /**
     * Services.
     */
    private final Iterable<Service> services;

    /**
     * Ctor.
     *
     * @param services Services.
     */
    public ServiceIndex(final Iterable<Service> services) {
        this.services = services;
    }

    @Override
    public String path() {
        return "/";
    }

    @Override
    public Resource resource(final String path) {
        final Resource resource;
        if ("/index.json".equals(path)) {
            resource = new Index();
        } else {
            resource = new Absent();
        }
        return resource;
    }

    /**
     * Services index JSON "/index.json".
     *
     * @since 0.1
     */
    private final class Index implements Resource {

        @Override
        public CompletableFuture<Response> get(final Headers headers) {
            final JsonArrayBuilder resources = Json.createArrayBuilder();
            for (final Service service : ServiceIndex.this.services) {
                resources.add(
                    Json.createObjectBuilder()
                        .add("@id", service.url())
                        .add("@type", service.type())
                );
            }
            final JsonObject json = Json.createObjectBuilder()
                .add("version", "3.0.0")
                .add("resources", resources)
                .build();
            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                JsonWriter writer = Json.createWriter(out)) {
                writer.writeObject(json);
                out.flush();
                return ResponseBuilder.ok()
                    .body(out.toByteArray())
                    .completedFuture();
            } catch (final IOException ex) {
                throw new IllegalStateException("Failed to serialize JSON to bytes", ex);
            }
        }

        @Override
        public CompletableFuture<Response> put(Headers headers, Content body) {
            return ResponseBuilder.methodNotAllowed().completedFuture();
        }
    }
}
