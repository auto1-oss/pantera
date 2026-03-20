/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http;

import java.util.regex.Pattern;

public interface PathPatterns {
    Pattern BASE = Pattern.compile("^/v2/$");
    Pattern MANIFESTS = Pattern.compile("^/v2/(?<name>.*)/manifests/(?<reference>.*)$");
    Pattern TAGS = Pattern.compile("^/v2/(?<name>.*)/tags/list$");
    Pattern BLOBS = Pattern.compile("^/v2/(?<name>.*)/blobs/(?<digest>(?!(uploads/)).*)$");
    Pattern UPLOADS = Pattern.compile("^/v2/(?<name>.*)/blobs/uploads/(?<uuid>[^/]*).*$");
    Pattern CATALOG = Pattern.compile("^/v2/_catalog$");
    Pattern REFERRERS = Pattern.compile("^/v2/(?<name>.*)/referrers/(?<digest>.*)$");
}
