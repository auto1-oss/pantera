/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.client;

import com.amihaiemil.eoyaml.YamlMapping;
import com.google.common.base.Strings;

import java.net.URI;

/**
 * Proxy repository remote configuration.
 */
public record RemoteConfig(URI uri, int priority, String username, String pwd) {

    public static RemoteConfig form(final YamlMapping yaml) {
        String url = yaml.string("url");
        if (Strings.isNullOrEmpty(url)) {
            throw new IllegalStateException("`url` is not specified for proxy remote");
        }
        int priority = 0;
        String s = yaml.string("priority");
        if (!Strings.isNullOrEmpty(s)) {
            priority = Integer.parseInt(s);
        }
        return new RemoteConfig(URI.create(url), priority, yaml.string("username"), yaml.string("password"));
    }
}
