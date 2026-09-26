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
package com.auto1.pantera.rpm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.rpm.RepoConfig;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import java.nio.charset.StandardCharsets;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Removing a package from an RPM repository needs the DELETE permission,
 * not READ.
 *
 * @since 2.2.9
 */
final class RpmSliceDeleteAuthzTest {

    /**
     * Package key.
     */
    private static final Key PKG = new Key.From("my_package.rpm");

    @Test
    void readOnlyUserCannotDelete() {
        final Storage asto = RpmSliceDeleteAuthzTest.withPackage();
        MatcherAssert.assertThat(
            "status",
            RpmSliceDeleteAuthzTest.delete(asto, Action.Standard.READ),
            new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "package kept",
            asto.exists(RpmSliceDeleteAuthzTest.PKG).join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void userWithDeletePermissionCanDelete() {
        MatcherAssert.assertThat(
            RpmSliceDeleteAuthzTest.delete(
                RpmSliceDeleteAuthzTest.withPackage(), Action.Standard.DELETE
            ),
            new IsEqual<>(RsStatus.ACCEPTED)
        );
    }

    private static Storage withPackage() {
        final Storage asto = new InMemoryStorage();
        asto.save(
            RpmSliceDeleteAuthzTest.PKG,
            new Content.From("rpm".getBytes(StandardCharsets.UTF_8))
        ).join();
        return asto;
    }

    private static RsStatus delete(final Storage asto, final Action granted) {
        final Policy<PermissionCollection> policy = user -> {
            final Permissions perms = new Permissions();
            perms.add(new AdapterBasicPermission("test", granted));
            return perms;
        };
        return new RpmSlice(
            asto, policy,
            (name, pass) -> Optional.of(new AuthUser(name, "test")),
            new RepoConfig.Simple(),
            Optional.empty()
        ).response(
            new RequestLine(RqMethod.DELETE, "/my_package.rpm?skip_update=true&force=true"),
            Headers.from(new Authorization.Basic("alice", "pw")),
            Content.EMPTY
        ).join().status();
    }
}
