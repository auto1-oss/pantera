/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.perms;

import com.auto1.pantera.security.perms.PanteraPermissionFactory;
import com.auto1.pantera.security.perms.PermissionConfig;
import com.auto1.pantera.security.perms.PermissionFactory;

/**
 * Docker registry permissions factory. Format in yaml:
 * <pre>{@code
 * docker_registry_permissions:
 *   docker-local: # repository name
 *     - * # catalog list: wildcard for all categories
 *   docker-global:
 *     - base
 *     - catalog
 * }</pre>
 * Possible
 * @since 0.18
 */
@PanteraPermissionFactory("docker_registry_permissions")
public final class DockerRegistryPermissionFactory implements
    PermissionFactory<DockerRegistryPermission.DockerRegistryPermissionCollection> {

    @Override
    public DockerRegistryPermission.DockerRegistryPermissionCollection newPermissions(
        final PermissionConfig config
    ) {
        final DockerRegistryPermission.DockerRegistryPermissionCollection res =
            new DockerRegistryPermission.DockerRegistryPermissionCollection();
        for (final String repo : config.keys()) {
            res.add(new DockerRegistryPermission(repo, config.sequence(repo)));
        }
        return res;
    }
}
