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
package com.auto1.pantera.api;

import io.vertx.ext.web.RoutingContext;

/**
 * Repository name.
 *
 * @since 0.26
 */
public interface RepositoryName {

    /**
     * Repository path parameter name.
     */
    String REPOSITORY_NAME = "rname";

    /**
     * The name of the repository.
     * @return String name
     */
    @Override
    String toString();

    /**
     * Repository name from request (from vertx {@link RoutingContext}) by `rname` path parameter.
     * @since 0.26
     */
    class FromRequest implements RepositoryName {

        /**
         * Repository name.
         */
        private final RoutingContext context;

        /**
         * Ctor.
         *
         * @param context Context
         */
        public FromRequest(final RoutingContext context) {
            this.context = context;
        }

        @Override
        public String toString() {
            return this.context.pathParam(RepositoryName.REPOSITORY_NAME);
        }
    }

    /**
     * Repository name from string.
     * @since 0.26
     */
    class Simple implements RepositoryName {

        /**
         * Repository name.
         */
        private final String name;

        /**
         * Ctor.
         * @param name Name
         */
        public Simple(final String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

}
