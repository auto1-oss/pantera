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
package com.auto1.pantera.nuget.http;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.headers.Authorization;
import java.util.Iterator;
import java.util.Map;
import java.util.Spliterator;
import java.util.function.Consumer;

/**
 * Single user basic authentication for usage in tests.
 *
 * @since 0.2
 */
public final class TestAuthentication extends Authentication.Wrap {


    public static final String USERNAME = "Aladdin";
    public static final String PASSWORD = "OpenSesame";

    public static final com.auto1.pantera.http.headers.Header HEADER = new Authorization.Basic(TestAuthentication.USERNAME, TestAuthentication.PASSWORD);
    public static final com.auto1.pantera.http.Headers HEADERS = com.auto1.pantera.http.Headers.from(HEADER);

    public TestAuthentication() {
        super(new Single(TestAuthentication.USERNAME, TestAuthentication.PASSWORD));
    }
}
