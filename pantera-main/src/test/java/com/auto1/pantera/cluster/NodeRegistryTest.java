/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cluster;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;

/**
 * Tests for {@link NodeRegistry}.
 */
class NodeRegistryTest {

    @Test
    void registersSelfOnConstruction() {
        final NodeRegistry reg = new NodeRegistry("node-1", "localhost");
        assertThat(reg.size(), equalTo(1));
        assertThat(reg.nodeId(), equalTo("node-1"));
    }

    @Test
    void autoGeneratesNodeId() {
        final NodeRegistry reg = new NodeRegistry("localhost");
        assertThat(reg.nodeId(), not(emptyString()));
        assertThat(reg.size(), equalTo(1));
    }

    @Test
    void heartbeatUpdatesTimestamp() {
        final NodeRegistry reg = new NodeRegistry("node-1", "localhost");
        reg.heartbeat();
        assertThat(reg.activeNodes(), hasSize(1));
    }

    @Test
    void activeNodesReturnsSelf() {
        final NodeRegistry reg = new NodeRegistry("node-1", "localhost");
        assertThat(reg.activeNodes(), hasSize(1));
        assertThat(reg.activeNodes().get(0).nodeId(), equalTo("node-1"));
        assertThat(reg.activeNodes().get(0).hostname(), equalTo("localhost"));
    }
}
