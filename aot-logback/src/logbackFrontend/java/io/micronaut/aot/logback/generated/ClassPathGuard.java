/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.aot.logback.generated;

import ch.qos.logback.classic.LoggerContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.zip.CRC32;

/**
 * What the generated {@code LogbackConfigurator} checks before it applies its literal configuration on a class
 * path that can change after the build: a later test run on the same output directory, stale output of an IDE, a
 * file mounted into a container image.
 *
 * <p>The precompiler copies this class into the application byte for byte, like {@code JoranFallback}, unless the
 * class path is closed. It looks where Joran would: through the class loader that defines logback-classic, which
 * is the one Logback's {@code DefaultJoranConfigurator} searches. It uses Logback and {@code java.base} only, no
 * lambdas and no invokedynamic, and a CRC-32 rather than a message digest, which would load the security providers
 * at startup. Its one Logback reference is {@code LoggerContext}, which is loaded by the time a configurator is
 * called.</p>
 *
 * <p>It keeps nothing between calls. Every call of the configurator looks again, as Joran reads the file again on
 * every configuration: a {@code logback.xml} that is edited while the application runs is what a later call, such
 * as Micronaut's logging refresh, hands over to Joran. That costs two resource lookups, one read of the file and
 * its CRC-32 on each call of the configurator, which Logback makes at startup and Micronaut on a logging refresh,
 * never on a log call.</p>
 */
public final class ClassPathGuard {

    private ClassPathGuard() {
    }

    /**
     * Whether Joran would now read the file that was compiled: no {@code logback-test.xml} is visible, and the
     * first visible {@code logback.xml} has the size and the CRC-32 of the compiled one. It looks again on every
     * call.
     *
     * @param size the size of the compiled {@code logback.xml}, in bytes
     * @param crc  its CRC-32
     * @return {@code false} when the configurator has to hand over to Logback's default lookup
     */
    public static boolean unchanged(long size, long crc) {
        try {
            ClassLoader loader = LoggerContext.class.getClassLoader();
            if (loader == null) {
                loader = ClassLoader.getSystemClassLoader();
            }
            if (loader.getResource("logback-test.xml") != null) {
                return false;
            }
            URL resource = loader.getResource("logback.xml");
            if (resource == null) {
                return false;
            }
            // Opened as Joran opens it: without the cache a jar URL connection would otherwise keep.
            URLConnection connection = resource.openConnection();
            connection.setUseCaches(false);
            CRC32 checksum = new CRC32();
            long length = 0;
            InputStream in = connection.getInputStream();
            try {
                byte[] buffer = new byte[8192];
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    checksum.update(buffer, 0, read);
                    length += read;
                }
            } finally {
                in.close();
            }
            return length == size && checksum.getValue() == crc;
        } catch (IOException | RuntimeException e) {
            // Whatever cannot be read is left to Joran, which reports it in its own words.
            return false;
        }
    }
}
