/*
 * Copyright 2017-2021 original authors
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
package io.micronaut.aot.std.sourcegen

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.codegen.AbstractSourceGeneratorSpec
import org.slf4j.LoggerFactory

class CachedEnvironmentSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    @Override
    AOTCodeGenerator newGenerator() {
        new CachedEnvironmentSourceGenerator()
    }

    def "generates code to enable environment caching"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            createsInitializer """private static void enableEnvironmentCaching() {
  io.micronaut.core.optim.StaticOptimizations.cacheEnvironment();
}
"""
        }
    }

    def "warns that the optimization is deprecated"() {
        given:
        def logger = (Logger) LoggerFactory.getLogger(CachedEnvironmentSourceGenerator)
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        when:
        generate()

        then:
        CachedEnvironmentSourceGenerator.isAnnotationPresent(Deprecated)
        appender.list.any {
            it.level == Level.WARN && it.formattedMessage.startsWith("The cached.environment optimization is deprecated.")
        }

        cleanup:
        logger.detachAppender(appender)
    }
}
