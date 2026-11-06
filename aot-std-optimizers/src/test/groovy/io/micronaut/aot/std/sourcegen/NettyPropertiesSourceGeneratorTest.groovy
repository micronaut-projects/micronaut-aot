package io.micronaut.aot.std.sourcegen

import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.codegen.AbstractSourceGeneratorSpec
import io.micronaut.context.ApplicationContextBuilder
import io.micronaut.context.ApplicationContextConfigurer

class NettyPropertiesSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    private static final String MACHINE_ID_PROPERTY = "io.netty.machineId"
    private static final String PROCESS_ID_PROPERTY = "io.netty.processId"

    @Override
    AOTCodeGenerator newGenerator() {
        new NettyPropertiesSourceGenerator()
    }

    def "generates random Netty system properties"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass(NettyPropertiesSourceGenerator.GENERATED_CLASS) {
                containingSources """
    if (System.getProperty("io.netty.machineId") == null) {
      System.setProperty("io.netty.machineId", randomMacAddress());
    }
    if (System.getProperty("io.netty.processId") == null) {
      System.setProperty("io.netty.processId", randomPid());
    }
"""
                containingSources randomMacAddress()
                containingSources randomPid()
            }
        }
    }

    def "can disable PID randomization"() {
        when:
        props.put(NettyPropertiesSourceGenerator.PROCESS_ID, "netty")
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass(NettyPropertiesSourceGenerator.GENERATED_CLASS) {
                containingSources """
    if (System.getProperty("io.netty.machineId") == null) {
      System.setProperty("io.netty.machineId", randomMacAddress());
    }
"""
                containingSources randomMacAddress()
                doesNotContainSources randomPid()
            }
        }
    }

    def "can disable machine id randomization"() {
        when:
        props.put(NettyPropertiesSourceGenerator.MACHINE_ID, "netty")
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass(NettyPropertiesSourceGenerator.GENERATED_CLASS) {
                containingSources """
    if (System.getProperty("io.netty.processId") == null) {
      System.setProperty("io.netty.processId", randomPid());
    }
"""
                containingSources randomPid()
                doesNotContainSources randomMacAddress()
            }
        }
    }

    def "can generate a hardcoded machine id"() {
        when:
        props.put(NettyPropertiesSourceGenerator.MACHINE_ID, "ab:cd:ef:00:11:22")
        props.put(NettyPropertiesSourceGenerator.PROCESS_ID, "netty")
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass(NettyPropertiesSourceGenerator.GENERATED_CLASS) {
                containingSources """
    if (System.getProperty("io.netty.machineId") == null) {
      System.setProperty("io.netty.machineId", "ab:cd:ef:00:11:22");
    }
"""
                doesNotContainSources randomMacAddress()
                doesNotContainSources randomPid()
            }
        }
    }

    def "can generate a hardcoded process id"() {
        when:
        props.put(NettyPropertiesSourceGenerator.PROCESS_ID, "85600")
        props.put(NettyPropertiesSourceGenerator.MACHINE_ID, "netty")

        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass(NettyPropertiesSourceGenerator.GENERATED_CLASS) {
                containingSources """
    if (System.getProperty("io.netty.processId") == null) {
      System.setProperty("io.netty.processId", "85600");
    }
"""
                doesNotContainSources randomMacAddress()
                doesNotContainSources randomPid()
            }
        }
    }

    def "keeps the Netty system properties the user already set (machine id: #machineId, process id: #processId)"() {
        given:
        def previousMachineId = System.getProperty(MACHINE_ID_PROPERTY)
        def previousProcessId = System.getProperty(PROCESS_ID_PROPERTY)
        props.put(NettyPropertiesSourceGenerator.MACHINE_ID, machineId)
        props.put(NettyPropertiesSourceGenerator.PROCESS_ID, processId)
        generate()
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass(NettyPropertiesSourceGenerator.GENERATED_CLASS) {
                containingSources "if (System.getProperty(\"$MACHINE_ID_PROPERTY\") == null)"
                containingSources "if (System.getProperty(\"$PROCESS_ID_PROPERTY\") == null)"
                compiles()
            }
        }
        def classLoader = new URLClassLoader([testDirectory.resolve("compiled").toUri().toURL()] as URL[], getClass().classLoader)
        def configurer = (ApplicationContextConfigurer) classLoader
            .loadClass("${packageName}.${NettyPropertiesSourceGenerator.GENERATED_CLASS}")
            .getDeclaredConstructor()
            .newInstance()

        when: "the user passed -Dio.netty.machineId and -Dio.netty.processId"
        System.setProperty(MACHINE_ID_PROPERTY, "00:11:22:33:44:55")
        System.setProperty(PROCESS_ID_PROPERTY, "4242")
        configurer.configure((ApplicationContextBuilder) null)

        then:
        System.getProperty(MACHINE_ID_PROPERTY) == "00:11:22:33:44:55"
        System.getProperty(PROCESS_ID_PROPERTY) == "4242"

        when: "the user passed neither"
        System.clearProperty(MACHINE_ID_PROPERTY)
        System.clearProperty(PROCESS_ID_PROPERTY)
        configurer.configure((ApplicationContextBuilder) null)

        then:
        System.getProperty(MACHINE_ID_PROPERTY) ==~ expectedMachineId
        System.getProperty(PROCESS_ID_PROPERTY) ==~ expectedProcessId

        cleanup:
        classLoader?.close()
        restoreSystemProperty(MACHINE_ID_PROPERTY, previousMachineId)
        restoreSystemProperty(PROCESS_ID_PROPERTY, previousProcessId)

        where:
        machineId           | processId | expectedMachineId                   | expectedProcessId
        'random'            | 'random'  | /\p{XDigit}{2}(:\p{XDigit}{2}){5}/  | /\d+/
        'ab:cd:ef:00:11:22' | '85600'   | /ab:cd:ef:00:11:22/                 | /85600/
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name)
        } else {
            System.setProperty(name, value)
        }
    }

    private static String randomMacAddress() {
        """private static String randomMacAddress() {
    Random rnd = new Random();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 6; i++) {
      sb.append(String.format("%02x", rnd.nextInt(256)));
      if (i < 5) {
        sb.append(":");
      }
    }
    return sb.toString();
  }"""
    }

    private static String randomPid() {
        """private static String randomPid() {
    return String.valueOf(new Random().nextInt(65536));
  }"""
    }
}
