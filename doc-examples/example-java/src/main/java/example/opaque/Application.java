package example.opaque;

import io.micronaut.runtime.Micronaut;

//tag::class[]
class Application {
    public static void main(String... args) {
        Micronaut.build(args)
            .environmentPropertySource(false)
            .mainClass(Application.class)
            .start();
    }
}
//end::class[]
