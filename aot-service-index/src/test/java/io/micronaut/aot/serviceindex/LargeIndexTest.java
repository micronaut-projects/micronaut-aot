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
package io.micronaut.aot.serviceindex;

import io.micronaut.aot.serviceindex.Fixtures.Run;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.micronaut.aot.serviceindex.Fixtures.BEAN_DEFINITIONS;
import static io.micronaut.aot.serviceindex.Fixtures.VALIDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Indexes too large for one method or one class (micronaut-aot#400): the generated code is split over methods and
 * classes, each of which stays within the JVM's limits, verifies, and builds the builder's index.
 */
class LargeIndexTest {

    @TempDir
    Path temporary;

    /** The shapes of a large {@code META-INF/micronaut}. */
    enum Shape {
        /** 20,000 entries of one type. */
        ONE_TYPE_OF_20_000(1, 20_000),
        /** 20,000 entries of 200 types. */
        TWO_HUNDRED_TYPES_OF_100(200, 100),
        /** 100,000 entries of one type: its array is indexed past 32,767. */
        ONE_TYPE_OF_100_000(1, 100_000),
        /** 10,000 types of one entry each: the code of each type counts, not only its names. */
        TEN_THOUSAND_TYPES_OF_1(10_000, 1);

        final int types;
        final int names;

        Shape(int types, int names) {
            this.types = types;
            this.names = names;
        }

        Fixtures.Content content() {
            Fixtures.Content content = Fixtures.content();
            for (int type = 0; type < types; type++) {
                List<String> entries = new ArrayList<>(names);
                for (int name = 0; name < names; name++) {
                    entries.add("fixture.$Bean%06d$%06d$Definition$Reference".formatted(type, name));
                }
                content.micronaut(types == 1 ? BEAN_DEFINITIONS : "fixture.Type%05d".formatted(type), entries);
            }
            return content;
        }
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void aLargeIndexIsSplitWithinTheLimitsOfTheJvm(Shape shape) throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), shape.content());

        ServiceIndexGenerator.Result result = Fixtures.generateFor(jar);

        assertEquals(ServiceIndexGenerator.Status.GENERATED, result.status(), result.message());
        int classes = 0;
        for (Map.Entry<String, byte[]> entry : result.entries().entrySet()) {
            if (entry.getKey().endsWith(".class")) {
                classes++;
                ClassModel model = ClassFile.of().parse(entry.getValue());
                assertTrue(model.constantPool().size() < 65_535, entry.getKey());
                for (MethodModel method : model.methods()) {
                    int length = method.findAttribute(Attributes.code()).map(CodeAttribute::codeLength).orElse(0);
                    assertTrue(length < 65_536, entry.getKey() + "." + method.methodName() + ": " + length);
                }
            }
        }
        assertTrue(classes > 3, "the index was not split over several classes: " + result.entries().keySet());
        assertEquals(List.of(), Fixtures.verify(result.entries()));
        Fixtures.Index loaded = Fixtures.load(List.of(jar), result.entries());
        Fixtures.Index built = Fixtures.build(List.of(jar), loaded.standardServices().keySet());
        assertEquals(shape.types * shape.names,
                loaded.micronautServices().values().stream().mapToInt(List::size).sum());
        assertEquals(built.micronautServices(), loaded.micronautServices());
        assertEquals(built.standardServices(), loaded.standardServices());
        assertEquals(List.of("app.jar"), loaded.classPath());
    }

    @Test
    void anApplicationStartsWithAnIndexOf100000Entries() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Shape.ONE_TYPE_OF_100_000.content());
        Fixtures.apply(jar, Fixtures.generateFor(jar));

        Run run = Fixtures.javaJar(jar, List.of(VALIDATE), List.of("micronaut:" + BEAN_DEFINITIONS));

        assertEquals(100_000, run.lookups().split(", ").length);
        assertTrue(run.usesTheIndex(), run::err);
        assertTrue(run.err().contains("META-INF/micronaut (100000 entries of 1 service types)"), run::err);
        assertTrue(run.err().contains("names validated against a scan"), run::err);
        assertFalse(run.warns(), run::err);
    }
}
