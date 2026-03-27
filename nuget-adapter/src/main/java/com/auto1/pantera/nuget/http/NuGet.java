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
package com.auto1.pantera.nuget.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.nuget.Repository;
import com.auto1.pantera.nuget.http.content.PackageContent;
import com.auto1.pantera.nuget.http.index.ServiceIndex;
import com.auto1.pantera.nuget.http.metadata.PackageMetadata;
import com.auto1.pantera.nuget.http.publish.PackagePublish;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.net.URL;
import java.util.Arrays;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * NuGet repository HTTP front end.
 */
public final class NuGet implements Slice {

    /**
     * Base URL.
     */
    private final URL url;

    /**
     * Repository.
     */
    private final Repository repository;

    /**
     * Access policy.
     */
    private final Policy<?> policy;

    /**
     * User identities.
     */
    private final Authentication users;

    /**
     * Token authentication.
     */
    private final TokenAuthentication tokenAuth;

    /**
     * Repository name.
     */
    private final String name;

    /**
     * Artifact events.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * @param url Base URL.
     * @param repository Storage for packages.
     * @param policy Access policy.
     * @param users User identities.
     * @param name Repository name
     * @param events Events queue
     */
    public NuGet(
        final URL url,
        final Repository repository,
        final Policy<?> policy,
        final Authentication users,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(url, repository, policy, users, null, name, events);
    }

    /**
     * Ctor with combined authentication support.
     * @param url Base URL.
     * @param repository Storage for packages.
     * @param policy Access policy.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param name Repository name
     * @param events Events queue
     */
    public NuGet(
        final URL url,
        final Repository repository,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this.url = url;
        this.repository = repository;
        this.policy = policy;
        this.users = basicAuth;
        this.tokenAuth = tokenAuth;
        this.name = name;
        this.events = events;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final String path = line.uri().getPath();
        final Resource resource = this.resource(path);
        final RqMethod method = line.method();
        if (method.equals(RqMethod.GET)) {
            return resource.get(headers);
        }
        if (method.equals(RqMethod.PUT)) {
            return resource.put(headers, body);
        }
        return ResponseBuilder.methodNotAllowed().completedFuture();
    }

    /**
     * Find resource by relative path.
     *
     * @param path Relative path.
     * @return Resource found by path.
     */
    private Resource resource(final String path) {
        final PackagePublish publish = new PackagePublish(this.repository, this.events, this.name);
        final PackageContent content = new PackageContent(this.url, this.repository);
        final PackageMetadata metadata = new PackageMetadata(this.repository, content);
        return new RoutingResource(
            path,
            new ServiceIndex(
                Arrays.asList(
                    new RouteService(this.url, publish, "PackagePublish/2.0.0"),
                    new RouteService(this.url, metadata, "RegistrationsBaseUrl/Versioned"),
                    new RouteService(this.url, content, "PackageBaseAddress/3.0.0")
                )
            ),
            this.auth(publish, Action.Standard.WRITE),
            this.auth(content, Action.Standard.READ),
            this.auth(metadata, Action.Standard.READ)
        );
    }

    /**
     * Create route supporting authentication.
     *
     * @param route Route requiring authentication.
     * @param action Action.
     * @return Authenticated route.
     */
    private Route auth(final Route route, final Action action) {
        if (this.tokenAuth != null) {
            return new CombinedAuthRoute(
                route,
                new OperationControl(this.policy, new AdapterBasicPermission(this.name, action)),
                this.users,
                this.tokenAuth
            );
        }
        return new BasicAuthRoute(
            route,
            new OperationControl(this.policy, new AdapterBasicPermission(this.name, action)),
            this.users
        );
    }
}
