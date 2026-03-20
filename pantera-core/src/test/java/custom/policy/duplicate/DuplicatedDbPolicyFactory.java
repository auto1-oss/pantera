/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package custom.policy.duplicate;

import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.security.policy.PanteraPolicyFactory;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.security.policy.PolicyFactory;
import java.security.PermissionCollection;

/**
 * Test policy.
 * @since 1.2
 */
@PanteraPolicyFactory("db-policy")
public final class DuplicatedDbPolicyFactory implements PolicyFactory {
    @Override
    public Policy<?> getPolicy(final Config config) {
        return (Policy<PermissionCollection>) uname -> null;
    }
}
