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
package custom.policy.file;

import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.security.policy.PanteraPolicyFactory;
import com.auto1.pantera.security.policy.PoliciesLoaderTest;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.security.policy.PolicyFactory;
import java.security.Permissions;

/**
 * Test policy.
 * @since 1.2
 */
@PanteraPolicyFactory("file-policy")
public final class FilePolicyFactory implements PolicyFactory {
    @Override
    public Policy<Permissions> getPolicy(final Config config) {
        return new PoliciesLoaderTest.TestPolicy();
    }
}
