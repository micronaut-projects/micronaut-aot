/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.aot.bytecode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.module.ModuleDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the transform knows about the classes of the JDK that runs it: which packages belong to the JDK, and the
 * public face of a class in one of them.
 *
 * <p>A package belongs to the JDK when a module of the boot layer holds it, whichever loader defines that module:
 * the boot loader, the platform loader, or the application loader itself, which defines modules such as
 * {@code jdk.compiler}. The JDK's application class loader takes every such package from its module, whatever the
 * class path holds. A loader that asks its own archive first, such as Micronaut Runner's, does so only for the
 * application loader's modules. So when the class path also holds a class in a package of the JDK, the copy that
 * loads depends on the loader and on the runtime image, and {@link LambdaDesugarer} treats every name of that
 * package as uncertain.</p>
 *
 * <p>A class is read from its module, which reads the runtime image ({@code jrt:/}), and parsed with the ClassFile
 * API. {@link ClassPathModel} resolves the JDK's classes for verification through {@link #open(String)}, so that
 * the gate sees the classes of every boot-layer module, not only those the platform loader can see. The caches live
 * as long as the JVM that runs the transform, because its JDK does not change between two builds; they hold only
 * the classes a run asked about.</p>
 */
final class JdkClasses {

    private static final String CALLER_SENSITIVE = "Ljdk/internal/reflect/CallerSensitive;";

    /** The module of every package of the boot layer, keyed by the package's internal name, such as {@code java/lang}. */
    private static final Map<String, Module> MODULES = bootLayerPackages();

    private static final ConcurrentHashMap<String, Optional<JdkClass>> CLASSES = new ConcurrentHashMap<>();

    private JdkClasses() {
    }

    /**
     * Whether a package belongs to the JDK: a module of the boot layer holds it.
     *
     * @param internalPackage the package, such as {@code java/util/function}, or the empty string
     * @return whether a boot-layer module holds the package
     */
    static boolean owns(String internalPackage) {
        return MODULES.containsKey(internalPackage);
    }

    /**
     * A class of a package of the JDK.
     *
     * @param internalName the class, such as {@code java/lang/String}
     * @return the class, or {@code null} when its package is not the JDK's or the JDK has no such class
     */
    static JdkClass find(String internalName) {
        Module module = MODULES.get(packageOf(internalName));
        if (module == null) {
            return null;
        }
        return CLASSES.computeIfAbsent(internalName, name -> Optional.ofNullable(read(module, name))).orElse(null);
    }

    /**
     * Opens the class file of a class of a package of the JDK, from its module. Class files are never encapsulated,
     * so this reads the classes of every package, exported or not.
     *
     * @param internalName the class, such as {@code com/sun/source/util/Trees}
     * @return the class file, which the caller closes, or {@code null} when the package is not the JDK's or the JDK
     * has no such class
     */
    static InputStream open(String internalName) {
        Module module = MODULES.get(packageOf(internalName));
        if (module == null) {
            return null;
        }
        try {
            return module.getResourceAsStream(internalName + ".class");
        } catch (IOException e) {
            return null;
        }
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    private static JdkClass read(Module module, String internalName) {
        byte[] bytes;
        try (InputStream in = module.getResourceAsStream(internalName + ".class")) {
            if (in == null) {
                return null;
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
        try {
            ClassModel model = ClassFile.of().parse(bytes);
            Map<String, Integer> methods = new HashMap<>();
            for (MethodModel method : model.methods()) {
                int flags = method.flags().flagsMask();
                if (isCallerSensitive(method)) {
                    flags |= JdkClass.CALLER_SENSITIVE;
                }
                methods.put(method.methodName().stringValue() + method.methodType().stringValue(), flags);
            }
            List<String> interfaces = new ArrayList<>(model.interfaces().size());
            for (ClassEntry entry : model.interfaces()) {
                interfaces.add(entry.asInternalName());
            }
            String packageName = packageOf(internalName).replace('/', '.');
            return new JdkClass(model.flags().flagsMask(), exported(module, packageName),
                    model.superclass().map(ClassEntry::asInternalName).orElse(null), List.copyOf(interfaces),
                    Map.copyOf(methods));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isCallerSensitive(MethodModel method) {
        Optional<RuntimeVisibleAnnotationsAttribute> annotations =
                method.findAttribute(Attributes.runtimeVisibleAnnotations());
        if (annotations.isEmpty()) {
            return false;
        }
        for (Annotation annotation : annotations.get().annotations()) {
            if (annotation.className().equalsString(CALLER_SENSITIVE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a module's descriptor exports a package to everyone. The descriptor is read, not the running module,
     * so that an {@code --add-exports} of the JVM that runs the transform does not change what it writes.
     */
    private static boolean exported(Module module, String packageName) {
        ModuleDescriptor descriptor = module.getDescriptor();
        if (descriptor == null) {
            return false;
        }
        for (ModuleDescriptor.Exports exports : descriptor.exports()) {
            if (!exports.isQualified() && exports.source().equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Module> bootLayerPackages() {
        Map<String, Module> packages = new HashMap<>(4096);
        for (Module module : ModuleLayer.boot().modules()) {
            for (String packageName : module.getPackages()) {
                packages.put(packageName.replace('.', '/'), module);
            }
        }
        return Map.copyOf(packages);
    }

    /**
     * The public face of one JDK class.
     *
     * @param flags      its access flags
     * @param exported   whether its module exports its package to everyone
     * @param superName  its superclass's internal name, or {@code null} for {@code java/lang/Object}
     * @param interfaces the internal names of the interfaces it implements directly
     * @param methods    the flags of each method it declares, keyed by name followed by descriptor, with
     *                   {@link #CALLER_SENSITIVE} set for a caller-sensitive one
     */
    record JdkClass(int flags, boolean exported, String superName, List<String> interfaces,
                    Map<String, Integer> methods) {

        /** Set in a method's flags when it is annotated {@code @jdk.internal.reflect.CallerSensitive}. */
        static final int CALLER_SENSITIVE = 0x1_0000;

        /**
         * A method the class declares itself.
         *
         * @param name       the method's name
         * @param descriptor its descriptor
         * @return its flags, or {@code null} when the class declares no such method
         */
        Integer method(String name, String descriptor) {
            return methods.get(name + descriptor);
        }

        /**
         * Whether the class declares a method that is neither abstract nor static, other than a constructor: for an
         * interface, what makes the JVM initialize it before a class that implements it.
         *
         * @return whether such a method is declared
         */
        boolean declaresConcreteInstanceMethod() {
            for (Map.Entry<String, Integer> method : methods.entrySet()) {
                if (method.getKey().charAt(0) != '<'
                        && (method.getValue() & (ClassFile.ACC_ABSTRACT | ClassFile.ACC_STATIC)) == 0) {
                    return true;
                }
            }
            return false;
        }
    }
}
