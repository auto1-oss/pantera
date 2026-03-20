/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.nuget.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;

import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

/**
 * Resource delegating requests handling to other resources, found by routing path.
 */
public final class RoutingResource implements Resource {

    /**
     * Resource path.
     */
    private final String path;

    /**
     * Routes.
     */
    private final Route[] routes;

    /**
     * Ctor.
     *
     * @param path Resource path.
     * @param routes Routes.
     */
    public RoutingResource(final String path, final Route... routes) {
        this.path = path;
        this.routes = Arrays.copyOf(routes, routes.length);
    }

    @Override
    public CompletableFuture<Response> get(final Headers headers) {
        return this.resource().get(headers);
    }

    @Override
    public CompletableFuture<Response> put(Headers headers, Content body) {
        return this.resource().put(headers, body);
    }

    /**
     * Find resource by path.
     *
     * @return Resource found by path.
     */
    private Resource resource() {
        return Arrays.stream(this.routes)
            .filter(r -> this.path.startsWith(r.path()))
            .max(Comparator.comparing(Route::path))
            .map(r -> r.resource(this.path))
            .orElse(new Absent());
    }

}
