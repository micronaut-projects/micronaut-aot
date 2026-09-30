package example.logback;

import io.micronaut.aot.logback.LogbackPrecompiler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * What a build tool plugin does with the Logback precompiler.
 */
public final class LogbackPrecompilation {

    private LogbackPrecompilation() {
    }

    public static LogbackPrecompiler.Result precompile(Path processedResources,
                                                       Path classesDirectory,
                                                       List<Path> runtimeClasspath,
                                                       Path outputDirectory,
                                                       Consumer<String> log) throws IOException {
        //tag::precompile[]
        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
            LogbackPrecompiler.Request.builder()
                .applicationOutput(List.of(processedResources, classesDirectory))
                .runtimeClasspath(runtimeClasspath)
                .targetRelease(25)
                .build());
        log.accept(result.message());
        result.writeTo(outputDirectory);
        //end::precompile[]
        return result;
    }
}
