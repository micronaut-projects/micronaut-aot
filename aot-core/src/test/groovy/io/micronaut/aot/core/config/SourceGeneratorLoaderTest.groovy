package io.micronaut.aot.core.config

import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.AOTContext
import io.micronaut.aot.core.AOTModule
import spock.lang.Specification

class SourceGeneratorLoaderTest extends Specification {

    def "sorts missing metadata selections after metadata-backed selections"() {
        given:
        def first = selection(new FirstGenerator(), false)
        def second = selection(new SecondGenerator(), true)
        def missing = new SourceGeneratorSelection(new MissingMetadataGenerator(), null, false, SourceGeneratorSelection.MISSING_METADATA)

        when:
        def sorted = [missing, second, first].sort(SourceGeneratorLoader::compareSelections)

        then:
        sorted*.generator*.class == [FirstGenerator, SecondGenerator, MissingMetadataGenerator]
    }

    private static SourceGeneratorSelection selection(AOTCodeGenerator generator, boolean enabled) {
        def module = generator.class.getAnnotation(AOTModule)
        new SourceGeneratorSelection(generator, module, enabled, enabled ? null : SourceGeneratorSelection.DISABLED_BY_CONFIGURATION)
    }

    @AOTModule(id = "first")
    private static class FirstGenerator implements AOTCodeGenerator {
        @Override
        void generate(AOTContext context) {
        }
    }

    @AOTModule(id = "second", dependencies = ["first"])
    private static class SecondGenerator implements AOTCodeGenerator {
        @Override
        void generate(AOTContext context) {
        }
    }

    private static class MissingMetadataGenerator implements AOTCodeGenerator {
        @Override
        void generate(AOTContext context) {
        }
    }
}
