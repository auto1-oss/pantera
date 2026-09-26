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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.http.client.egress.EgressPolicy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * {@link RemoteUrlPolicy} reads the egress policy through a supplier on
 * every check, so an admin edit applies to the next repository write.
 *
 * @since 2.2.9
 */
final class RemoteUrlPolicySupplierTest {

    @Test
    void policyChangesApplyToTheNextCheck() {
        final AtomicReference<EgressPolicy> policy = new AtomicReference<>(EgressPolicy.defaults());
        final RemoteUrlPolicy remote = new RemoteUrlPolicy(policy::get);
        final List<String> urls = List.of("http://10.0.0.7/repo/");
        MatcherAssert.assertThat(
            "a private literal address passes while the policy is not strict",
            remote.syntaxError(urls).isPresent(), new IsEqual<>(false)
        );
        policy.set(new EgressPolicy(true, Set.of()));
        MatcherAssert.assertThat(
            "the same address is refused once the policy is strict",
            remote.syntaxError(urls).isPresent(), new IsEqual<>(true)
        );
    }

    @Test
    void metadataDestinationIsRefusedRegardlessOfPolicy() {
        final RemoteUrlPolicy remote = new RemoteUrlPolicy(EgressPolicy::defaults);
        MatcherAssert.assertThat(
            "the cloud metadata address is never a valid remote",
            remote.syntaxError(List.of("http://169.254.169.254/latest/")).isPresent(),
            new IsEqual<>(true)
        );
    }
}
