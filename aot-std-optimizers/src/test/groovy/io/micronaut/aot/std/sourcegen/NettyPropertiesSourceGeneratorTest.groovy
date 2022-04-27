package io.micronaut.aot.std.sourcegen

import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.codegen.AbstractSourceGeneratorSpec

class NettyPropertiesSourceGeneratorTest extends AbstractSourceGeneratorSpec {

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
    System.setProperty("io.netty.machineId", randomMacAddress());
    System.setProperty("io.netty.processId", randomPid());
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
    System.setProperty("io.netty.machineId", randomMacAddress());
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
    System.setProperty("io.netty.processId", randomPid());
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
    System.setProperty("io.netty.machineId", "ab:cd:ef:00:11:22");
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
    System.setProperty("io.netty.processId", "85600");
"""
                doesNotContainSources randomMacAddress()
                doesNotContainSources randomPid()
            }
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
