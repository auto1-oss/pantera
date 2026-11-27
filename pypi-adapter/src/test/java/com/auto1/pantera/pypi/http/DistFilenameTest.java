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
package com.auto1.pantera.pypi.http;

import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Project and version of the distribution files PyPI has served, read from
 * their names.
 *
 * @since 2.2.15
 */
final class DistFilenameTest {

    @ParameterizedTest
    @CsvSource({
        "requests-2.31.0.tar.gz,requests,2.31.0",
        "Twisted-8.2.0.tar.bz2,twisted,8.2.0",
        "Pygments-0.5.tgz,pygments,0.5",
        "pexpect-2.0.tbz,pexpect,2.0",
        "pyzmq-26.0.0.tar.xz,pyzmq,26.0.0",
        "lzma_pkg-1.0.txz,lzma-pkg,1.0",
        "oldpkg-0.9.tar.Z,oldpkg,0.9",
        "plain-1.0.tar,plain,1.0",
        "zope.interface-3.5.0.zip,zope-interface,3.5.0",
        "python-dateutil-1.5.tar.gz,python-dateutil,1.5",
        "six-1.16.0-py2.py3-none-any.whl,six,1.16.0",
        "setuptools-0.6c11-py2.7.egg,setuptools,0.6c11",
        "pywin32-214.win32-py2.7.exe,pywin32,214",
        "numpy-1.6.2.win32-py2.7.exe,numpy,1.6.2",
        "python-dateutil-1.5.win32.exe,python-dateutil,1.5",
        "foo-1.0.post1.win-amd64-py3.8.exe,foo,1.0.post1",
        "foo-1.0.linux-x86_64.exe,foo,1.0",
        "pyodbc-3.0.6.win32-py2.7.msi,pyodbc,3.0.6",
        "Twisted-12.0.0.win-amd64-py2.7.msi,twisted,12.0.0",
        "python-dateutil-1.5-1.noarch.rpm,python-dateutil,1.5",
        "foo-1.0-1.src.rpm,foo,1.0",
        "numpy-1.6.2-py2.7-python.org-macosx10.3.dmg,numpy,1.6.2",
        "python-foo_1.0-1_all.deb,python-foo,1.0",
        "foo_1.0_amd64.deb,foo,1.0"
    })
    void readsProjectAndVersion(final String file, final String project, final String version) {
        final DistFilename dist = new DistFilename(file);
        MatcherAssert.assertThat(
            "project", dist.project(), new IsEqual<>(Optional.of(project))
        );
        MatcherAssert.assertThat(
            "version", dist.version(), new IsEqual<>(Optional.of(version))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"noversion.tar.gz", "noversion.whl", "installer.exe", "x.deb"})
    void hasNoVersionWithoutOne(final String file) {
        MatcherAssert.assertThat(
            new DistFilename(file).version(), new IsEqual<>(Optional.empty())
        );
    }
}
