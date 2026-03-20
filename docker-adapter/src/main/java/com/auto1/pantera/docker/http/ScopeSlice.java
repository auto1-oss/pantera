/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http;

import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.security.Permission;

/**
 * Slice requiring authorization.
 */
public interface ScopeSlice extends Slice {

    Permission permission(RequestLine line);

}
