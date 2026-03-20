/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.group;

import com.auto1.pantera.http.timeout.AutoBlockRegistry;
import com.auto1.pantera.http.timeout.AutoBlockSettings;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

final class MemberSliceTest {

    @Test
    void reportsOpenCircuitFromRegistry() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            1, Duration.ofMinutes(5), Duration.ofMinutes(60)
        ));
        final MemberSlice member = new MemberSlice("test-member", null, registry);
        assertThat(member.isCircuitOpen(), is(false));
        registry.recordFailure("test-member");
        assertThat(member.isCircuitOpen(), is(true));
    }

    @Test
    void recordsSuccessViaRegistry() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            1, Duration.ofMinutes(5), Duration.ofMinutes(60)
        ));
        final MemberSlice member = new MemberSlice("test-member", null, registry);
        registry.recordFailure("test-member");
        assertThat(member.isCircuitOpen(), is(true));
        member.recordSuccess();
        assertThat(member.isCircuitOpen(), is(false));
    }

    @Test
    void recordsFailureViaRegistry() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            2, Duration.ofMinutes(5), Duration.ofMinutes(60)
        ));
        final MemberSlice member = new MemberSlice("test-member", null, registry);
        member.recordFailure();
        assertThat(member.isCircuitOpen(), is(false));
        member.recordFailure();
        assertThat(member.isCircuitOpen(), is(true));
    }

    @Test
    void reportsCircuitState() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            1, Duration.ofMinutes(5), Duration.ofMinutes(60)
        ));
        final MemberSlice member = new MemberSlice("test-member", null, registry);
        assertThat(member.circuitState(), equalTo("ONLINE"));
        registry.recordFailure("test-member");
        assertThat(member.circuitState(), equalTo("BLOCKED"));
    }
}
