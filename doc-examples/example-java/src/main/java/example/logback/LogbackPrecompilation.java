package example.logback;

import io.micronaut.aot.logback.LogbackPrecompiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What a Micronaut build plugin does with the Logback precompiler.
 */
final class LogbackPrecompilation {

    private LogbackPrecompilation() {
    }

    static LogbackPrecompiler.Result precompile(Path processedResources,
                                                Path classesDirectory,
                                                List<Path> runtimeClasspath,
                                                Path outputDirectory,
                                                Consumer<String> log,
                                                Consumer<String> warn) throws IOException {
        //tag::precompile[]
        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
            LogbackPrecompiler.Request.builder()
                .applicationOutput(List.of(processedResources, classesDirectory))
                .runtimeClasspath(runtimeClasspath)
                .targetRelease(25)
                .build());
        log.accept(result.message());
        result.warnings().forEach(warn);
        for (Map.Entry<String, byte[]> entry : result.entries().entrySet()) {
            Path file = outputDirectory.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        //end::precompile[]
        return result;
    }
}
