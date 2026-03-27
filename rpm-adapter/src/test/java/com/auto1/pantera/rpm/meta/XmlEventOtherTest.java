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
package com.auto1.pantera.rpm.meta;

import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.rpm.Digest;
import com.auto1.pantera.rpm.hm.IsXmlEqual;
import com.auto1.pantera.rpm.pkg.FilePackage;
import com.auto1.pantera.rpm.pkg.FilePackageHeader;
import com.fasterxml.aalto.stax.OutputFactoryImpl;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLStreamException;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link XmlEvent.Other}.
 * @since 1.5
 */
class XmlEventOtherTest {

    /**
     * Temporary directory for all tests.
     */
    @TempDir
    Path tmp;

    @Test
    void writesPackageInfo() throws XMLStreamException, IOException {
        final Path res = Files.createTempFile(this.tmp, "others", ".xml");
        final Path file = new TestResource("abc-1.01-26.git20200127.fc32.ppc64le.rpm").asPath();
        try (OutputStream out = Files.newOutputStream(res)) {
            final XMLEventWriter writer = new OutputFactoryImpl().createXMLEventWriter(out);
            new XmlEvent.Other().add(
                writer,
                new FilePackage.Headers(new FilePackageHeader(file).header(), file, Digest.SHA256)
            );
            writer.close();
        }
        MatcherAssert.assertThat(
            res,
            new IsXmlEqual(
                String.join(
                    "\n",
                    "<package pkgid=\"b9d10ae3485a5c5f71f0afb1eaf682bfbea4ea667cc3c3975057d6e3d8f2e905\" name=\"abc\" arch=\"ppc64le\">",
                    "<version epoch=\"0\" ver=\"1.01\" rel=\"26.git20200127.fc32\"/>",
                    "</package>"
                )
            )
        );
    }

}
