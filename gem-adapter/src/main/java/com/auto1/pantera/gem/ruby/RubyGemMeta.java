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
package com.auto1.pantera.gem.ruby;

import com.auto1.pantera.gem.GemMeta;
import java.nio.file.Path;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyObject;
import org.jruby.javasupport.JavaEmbedUtils;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * JRuby implementation of GemInfo metadata parser.
 * @since 1.0
 * @todo #103:30min Inspect rubygems API response to add more fields.
 *  Check responses for different Gem requests for origin rubygems.org
 *  or reverse-engineer Gem repository Ruby code to understand all fields
 *  that should be added to gems API response. Now all mandatory fields
 *  are present in this metadata genrator but different gem responses may have
 *  optional fields. E.g. https://rubygems.org/api/v1/gems/builder.json
 */
public final class RubyGemMeta implements GemMeta, SharedRuntime.RubyPlugin {

    /**
     * Ruby runtime.
     */
    private final Ruby ruby;

    /**
     * Ctor.
     * @param ruby Runtime
     */
    public RubyGemMeta(final Ruby ruby) {
        this.ruby = ruby;
    }

    @Override
    public MetaInfo info(final Path gem) {
        // SECURITY (2.2.9): the gem path is an attacker-influenced stored key.
        // It MUST be passed as a Ruby data object, never string-interpolated
        // into eval'd source — the old `Gem::Package.new('<path>').spec` let a
        // path containing a single quote inject arbitrary Ruby (RCE). Only the
        // static class constant is evaluated; the path crosses as a String.
        final IRubyObject pkgclass = JavaEmbedUtils.newRuntimeAdapter()
            .eval(this.ruby, "Gem::Package");
        final IRubyObject pkg = JavaEmbedUtils.invokeMethod(
            this.ruby, pkgclass, "new",
            new Object[]{JavaEmbedUtils.javaToRuby(this.ruby, gem.toString())},
            IRubyObject.class
        );
        final RubyObject spec = (RubyObject) JavaEmbedUtils.invokeMethod(
            this.ruby, pkg, "spec", new Object[0], IRubyObject.class
        );
        return new RubyMetaInfo(spec);
    }

    @Override
    public String identifier() {
        return this.getClass().getCanonicalName();
    }

    @Override
    public void initialize() {
        JavaEmbedUtils.newRuntimeAdapter()
            .eval(this.ruby, "require 'rubygems/package.rb'");
    }

    /**
     * Meta info implementation for Ruby spec object.
     * @since 1.0
     */
    private static final class RubyMetaInfo implements MetaInfo {

        /**
         * Ruby meta spec object.
         */
        private final RubyObject spec;

        /**
         * New meta info.
         * @param spec Spec object
         */
        RubyMetaInfo(final RubyObject spec) {
            this.spec = spec;
        }

        @Override
        public void print(final MetaFormat fmt) {
            fmt.print("name", this.string("@name"));
            fmt.print(
                "version",
                this.spec.getInstanceVariable("@version")
                    .getInstanceVariables()
                    .getInstanceVariable("@version")
                    .asJavaString()
            );
            fmt.print("platform", this.string("@platform"));
            fmt.print("authors", this.strings("@authors"));
            // description, homepage and licenses are optional in a gemspec:
            // a nil value used to fail the whole upload with a TypeError.
            fmt.print("info", this.string("@description"));
            fmt.print("licenses", this.strings("@licenses"));
            fmt.print("homepage_uri", this.string("@homepage"));
        }

        /**
         * String spec attribute; empty when unset or nil.
         * @param name Instance variable name
         * @return Value
         */
        private String string(final String name) {
            final IRubyObject val = this.spec.getInstanceVariable(name);
            final String res;
            if (val == null || val.isNil()) {
                res = "";
            } else {
                res = val.asString().asJavaString();
            }
            return res;
        }

        /**
         * String-list spec attribute; empty when unset or nil.
         * @param name Instance variable name
         * @return Values
         */
        private String[] strings(final String name) {
            final IRubyObject val = this.spec.getInstanceVariable(name);
            final String[] res;
            if (val == null || val.isNil()) {
                res = new String[0];
            } else {
                res = rubyToJavaStringArray(val.convertToArray());
            }
            return res;
        }

        /**
         * Convert JRuby array to Java array of stirngs.
         * @param src JRuby array
         * @return String array
         */
        private static String[] rubyToJavaStringArray(final RubyArray<?> src) {
            final IRubyObject[] jarr = src.toJavaArray();
            final String[] res = new String[jarr.length];
            for (int id = 0; id < jarr.length; ++id) {
                if (jarr[id].isNil()) {
                    res[id] = "";
                } else {
                    res[id] = jarr[id].asString().asJavaString();
                }
            }
            return res;
        }
    }
}
