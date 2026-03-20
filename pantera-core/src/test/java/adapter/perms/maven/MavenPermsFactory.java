/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package adapter.perms.maven;

import com.auto1.pantera.security.perms.ArtipiePermissionFactory;
import com.auto1.pantera.security.perms.PermissionConfig;
import com.auto1.pantera.security.perms.PermissionFactory;
import java.security.AllPermission;
import java.security.PermissionCollection;

/**
 * Test permission.
 * @since 1.2
 */
@ArtipiePermissionFactory("maven-perm")
public final class MavenPermsFactory implements PermissionFactory<PermissionCollection> {
    @Override
    public PermissionCollection newPermissions(final PermissionConfig config) {
        return new AllPermission().newPermissionCollection();
    }
}
