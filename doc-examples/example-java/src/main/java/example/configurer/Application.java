package example.configurer;

import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.context.annotation.ContextConfigurer;
import io.micronaut.runtime.Micronaut;

//tag::class[]
class Application {
    @ContextConfigurer
    public static class MyConfigurer implements ApplicationContextConfigurer {
        @Override
        public void configure(ApplicationContextBuilder context) {
            context.environmentPropertySource(false);
        }
    }

    public static void main(String... args) {
        Micronaut.run(Application.class, args);
    }
}
//end::class[]
