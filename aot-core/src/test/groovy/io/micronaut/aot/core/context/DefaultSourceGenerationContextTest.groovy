package io.micronaut.aot.core.context

import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.codegen.AbstractSourceGeneratorSpec
import io.micronaut.aot.core.codegen.ApplicationContextConfigurerGenerator

import java.util.concurrent.Callable
import java.util.concurrent.Executors

class DefaultSourceGenerationContextTest extends AbstractSourceGeneratorSpec {

    private List<AOTCodeGenerator> generators = []

    @Override
    AOTCodeGenerator newGenerator() {
        new ApplicationContextConfigurerGenerator(generators)
    }

    def "test parallel diagnostic add"() {
        when:

        def threadsCount = 50;

        List<Callable<Object>> tasks = new ArrayList<>(threadsCount);
        for (int i = 0; i < threadsCount; i++) {
            tasks.add(addDiagnostics("category", "message"));
            tasks.add(addDiagnostics("category2", "message2"));
            tasks.add(addDiagnostics("category3", "message3"));
        }

        def executorService = Executors.newFixedThreadPool(8);
        executorService.invokeAll(tasks)

        then:
        context.diagnostics
        context.diagnostics.size() == 3
        context.diagnostics.category.size() == 50
        context.diagnostics.category2.size() == 50
        context.diagnostics.category3.size() == 50
    }

    Callable<Object> addDiagnostics(String category, String message) {
        return Executors.callable(() -> {
            context.addDiagnostics(category, message)
        })
    }
}
