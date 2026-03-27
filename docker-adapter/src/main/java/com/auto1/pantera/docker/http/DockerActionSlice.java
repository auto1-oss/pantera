/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http;

import com.auto1.pantera.docker.Docker;

public abstract class DockerActionSlice implements ScopeSlice {

    protected final Docker docker;

    public DockerActionSlice(Docker docker) {
        this.docker = docker;
    }
}
