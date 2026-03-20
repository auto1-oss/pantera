/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package adapter.perms.duplicate;

import com.auto1.pantera.security.perms.PanteraPermissionFactory;
import com.auto1.pantera.security.perms.PermissionConfig;
import com.auto1.pantera.security.perms.PermissionFactory;
import java.security.AllPermission;
import java.security.PermissionCollection;

/**
 * Test permission.
 * @since 1.2
 */
@PanteraPermissionFactory("docker-perm")
public final class DuplicatedDockerPermsFactory implements PermissionFactory<PermissionCollection> {
    @Override
    public PermissionCollection newPermissions(final PermissionConfig config) {
        return new AllPermission().newPermissionCollection();
    }
}
