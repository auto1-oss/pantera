/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package custom.policy.file;

import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.security.policy.ArtipiePolicyFactory;
import com.auto1.pantera.security.policy.PoliciesLoaderTest;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.security.policy.PolicyFactory;
import java.security.Permissions;

/**
 * Test policy.
 * @since 1.2
 */
@ArtipiePolicyFactory("file-policy")
public final class FilePolicyFactory implements PolicyFactory {
    @Override
    public Policy<Permissions> getPolicy(final Config config) {
        return new PoliciesLoaderTest.TestPolicy();
    }
}
