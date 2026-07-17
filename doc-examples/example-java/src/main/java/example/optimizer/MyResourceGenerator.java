package example.optimizer;

import io.micronaut.aot.core.AOTCodeGenerator;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.Option;

//tag::class[]
@AOTModule(
    id = MyResourceGenerator.ID,
    options = {
        @Option(key = "greeter.message", sampleValue = "Hello, world!", description = "The message to write")
    }
)
public class MyResourceGenerator implements AOTCodeGenerator {
    public static final String ID = "my.resource.generator";

    @Override
    public void generate(AOTContext context) {
        context.registerGeneratedResource("hello.txt", file -> {
            String message = context.getConfiguration()
                .mandatoryValue("greeter.message");
            try {
                java.nio.file.Files.writeString(file.toPath(), message);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
//end::class[]
