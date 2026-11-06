/*
 * Copyright 2017-2021 original authors
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
package io.micronaut.aot.std.sourcegen;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.model.ConfigurationModel;
import ch.qos.logback.classic.model.LoggerContextListenerModel;
import ch.qos.logback.classic.model.LoggerModel;
import ch.qos.logback.classic.model.RootLoggerModel;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.classic.util.DefaultJoranConfigurator;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.joran.event.SaxEventRecorder;
import ch.qos.logback.core.joran.spi.DefaultClass;
import ch.qos.logback.core.joran.spi.DefaultNestedComponentRegistry;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.joran.spi.NoAutoStart;
import ch.qos.logback.core.joran.util.StringToObjectConverter;
import ch.qos.logback.core.joran.util.beans.BeanDescription;
import ch.qos.logback.core.joran.util.beans.BeanDescriptionCache;
import ch.qos.logback.core.joran.util.beans.BeanUtil;
import ch.qos.logback.core.model.AppenderModel;
import ch.qos.logback.core.model.AppenderRefModel;
import ch.qos.logback.core.model.ImplicitModel;
import ch.qos.logback.core.model.Model;
import ch.qos.logback.core.model.NamedComponentModel;
import ch.qos.logback.core.model.StatusListenerModel;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.ContextAware;
import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.LifeCycle;
import ch.qos.logback.core.spi.ScanException;
import ch.qos.logback.core.status.OnConsoleStatusListener;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusListener;
import ch.qos.logback.core.util.ContextUtil;
import ch.qos.logback.core.util.OptionHelper;
import ch.qos.logback.core.util.StatusListenerConfigHelper;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import org.jspecify.annotations.Nullable;
import org.xml.sax.InputSource;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Translates a Logback XML configuration into the body of a {@link Configurator#configure(LoggerContext)}
 * method which builds the same logger context as Joran, Logback's XML configurator.
 *
 * <p>The translation fails closed: whenever the configuration uses something which cannot be translated
 * faithfully, an {@link UnsupportedConfigurationException} is thrown, so that Joran configures Logback
 * from the XML file at runtime.</p>
 *
 * <p>The generated code mirrors Joran's model handlers: status and context listeners are configured first,
 * then appenders which are referenced (in document order) and loggers. Appenders are named, nested
 * components are given the context, their parent and started before they are attached.</p>
 */
final class Logback14GeneratorHelper {

    private static final String CONFIGURATION_FILE_PROPERTY = "logback.configurationFile";
    private static final String DEBUG_PROPERTY = "logback.debug";
    private static final String ROOT_LOGGER_VARIABLE = "_rootLogger";
    private static final String INHERITED = "INHERITED";
    private static final String NULL = "NULL";
    private static final String FALSE = "false";
    private static final String PARENT_PROPERTY = "parent";
    private static final String VARIABLE_SUBSTITUTION = "${";
    private static final List<String> FILE_APPENDER_CLASSES = List.of(
        "ch.qos.logback.core.FileAppender",
        "ch.qos.logback.core.rolling.RollingFileAppender"
    );

    private final LoggerContext analysisContext;
    private final ContextAware conversionContext;
    private final BeanDescriptionCache beanDescriptionCache;
    private final DefaultNestedComponentRegistry defaultNestedComponentRegistry;
    private final CodeBlock.Builder code = CodeBlock.builder();
    private final Map<Model, String> variableNames = new IdentityHashMap<>();
    private final Set<String> usedVariableNames = new HashSet<>(List.of("loggerContext", "context", "joranConfigurator", "logbackDebug"));
    private final Map<String, AppenderModel> declaredAppenders = new LinkedHashMap<>();
    private final Set<String> referencedAppenders = new HashSet<>();
    private final Map<String, String> appenderVariables = new HashMap<>();
    private final Map<String, Set<String>> loggerAppenderRefs = new LinkedHashMap<>();

    private Logback14GeneratorHelper(LoggerContext analysisContext, DefaultNestedComponentRegistry defaultNestedComponentRegistry) {
        this.analysisContext = analysisContext;
        var contextAware = new ContextAwareBase();
        contextAware.setContext(analysisContext);
        this.conversionContext = contextAware;
        this.beanDescriptionCache = new BeanDescriptionCache(analysisContext);
        this.defaultNestedComponentRegistry = defaultNestedComponentRegistry;
    }

    /**
     * Generates the {@code configure} method for the given Logback XML configuration.
     *
     * @param logbackFile the Logback XML configuration
     * @return the method
     * @throws UnsupportedConfigurationException if the configuration cannot be translated faithfully
     */
    static MethodSpec configureMethod(URL logbackFile) {
        var context = new LoggerContext();
        var modelBuilder = new ModelBuilder();
        modelBuilder.setContext(context);
        Model model;
        try (InputStream inputStream = logbackFile.openStream()) {
            var inputSource = new InputSource(inputStream);
            inputSource.setSystemId(logbackFile.toExternalForm());
            SaxEventRecorder recorder = modelBuilder.populateSaxEventRecorder(inputSource);
            model = modelBuilder.buildModelFromSaxEventList(recorder.getSaxEventList());
        } catch (JoranException | IOException e) {
            throw new UnsupportedConfigurationException("could not be parsed: " + e.getMessage());
        }
        failOnJoranWarnings(context);
        if (model == null || model.getClass() != ConfigurationModel.class) {
            throw new UnsupportedConfigurationException("does not have <configuration> as its root element");
        }
        var helper = new Logback14GeneratorHelper(context, modelBuilder.defaultNestedComponentRegistry());
        MethodSpec method = helper.translate((ConfigurationModel) model);
        failOnJoranWarnings(context);
        return method;
    }

    private static void failOnJoranWarnings(LoggerContext context) {
        for (Status status : context.getStatusManager().getCopyOfStatusList()) {
            if (status.getLevel() >= Status.WARN) {
                throw new UnsupportedConfigurationException("makes Logback report \"" + status.getMessage() + "\"");
            }
        }
    }

    private MethodSpec translate(ConfigurationModel configuration) {
        declareAppenders(configuration);
        collectReferencedAppenders(configuration);
        checkFileCollisions();
        emitConfigurationFileDelegation();
        emitConfiguration(configuration);
        // Joran handles listeners in a first phase, then appenders and loggers in document order
        for (Model model : configuration.getSubModels()) {
            if (model.getClass() == StatusListenerModel.class) {
                emitStatusListener((StatusListenerModel) model);
            } else if (model.getClass() == LoggerContextListenerModel.class) {
                emitLoggerContextListener((LoggerContextListenerModel) model);
            }
        }
        for (Model model : configuration.getSubModels()) {
            if (model.getClass() == AppenderModel.class) {
                emitAppender((AppenderModel) model);
            } else if (model.getClass() == LoggerModel.class) {
                emitLogger((LoggerModel) model);
            } else if (model.getClass() == RootLoggerModel.class) {
                emitRootLogger((RootLoggerModel) model);
            }
        }
        loggerAppenderRefs.forEach((logger, appenders) -> {
            for (String appender : appenders) {
                code.addStatement("$L.addAppender($L)", logger, appenderVariables.get(appender));
            }
        });
        code.addStatement("return $T.DO_NOT_INVOKE_NEXT_IF_ANY", Configurator.ExecutionStatus.class);
        return MethodSpec.methodBuilder("configure")
            .addModifiers(Modifier.PUBLIC)
            .returns(Configurator.ExecutionStatus.class)
            .addParameter(LoggerContext.class, "loggerContext")
            .addCode(code.build())
            .build();
    }

    private void declareAppenders(ConfigurationModel configuration) {
        for (Model model : configuration.getSubModels()) {
            Class<?> type = model.getClass();
            if (type == AppenderModel.class) {
                var appender = (AppenderModel) model;
                String name = requireName(appender.getName(), "an appender without a name");
                if (declaredAppenders.putIfAbsent(name, appender) != null) {
                    throw unsupported("declares appender [" + name + "] more than once");
                }
            } else if (type != StatusListenerModel.class && type != LoggerContextListenerModel.class
                && type != LoggerModel.class && type != RootLoggerModel.class) {
                throw unsupportedElement(model);
            }
        }
    }

    /**
     * Joran instantiates an appender if and only if an appender-ref, anywhere in a logger, the root logger
     * or another appender (even an unreferenced one), refers to it.
     */
    private void collectReferencedAppenders(ConfigurationModel configuration) {
        for (Model model : configuration.getSubModels()) {
            if (model.getClass() == AppenderModel.class || model.getClass() == LoggerModel.class || model.getClass() == RootLoggerModel.class) {
                collectReferencedAppenders(model);
            }
        }
    }

    private void collectReferencedAppenders(Model model) {
        if (model instanceof AppenderRefModel appenderRef) {
            referencedAppenders.add(requireName(appenderRef.getRef(), "an appender-ref without a ref"));
        }
        for (Model subModel : model.getSubModels()) {
            collectReferencedAppenders(subModel);
        }
    }

    /**
     * Joran skips file appenders which write to the file (or roll over to the file name pattern) of an appender
     * declared earlier.
     */
    private void checkFileCollisions() {
        Map<String, String> files = new HashMap<>();
        Map<String, String> fileNamePatterns = new HashMap<>();
        declaredAppenders.forEach((name, appender) -> {
            if (FILE_APPENDER_CLASSES.contains(appender.getClassName())) {
                checkFileCollision(files, name, appender, "file");
                checkFileCollision(fileNamePatterns, name, appender, "fileNamePattern");
            }
        });
    }

    private void checkFileCollision(Map<String, String> valueToAppender, String appenderName, AppenderModel appender, String tag) {
        Optional<Model> option = Stream.concat(
                appender.getSubModels().stream(),
                appender.getSubModels().stream().flatMap(subModel -> subModel.getSubModels().stream())
            )
            .filter(model -> model instanceof ImplicitModel && tag.equals(model.getTag()))
            .findFirst();
        if (option.isPresent()) {
            String value = requireLiteral(option.get().getBodyText());
            if (value == null) {
                throw unsupported("declares appender [" + appenderName + "] with an empty <" + tag + ">");
            }
            String previous = valueToAppender.putIfAbsent(value, appenderName);
            if (previous != null) {
                throw unsupported("declares appenders [" + previous + "] and [" + appenderName + "] with the same " + tag + " [" + value + "]");
            }
        }
    }

    /**
     * Joran looks for {@code logback.configurationFile}, then {@code logback-test.xml}, then the translated
     * file, which is no longer on the classpath. When Joran finds none of them, the generated configuration
     * applies.
     */
    private void emitConfigurationFileDelegation() {
        code.beginControlFlow("if ($T.getSystemProperty($S) != null)", OptionHelper.class, CONFIGURATION_FILE_PROPERTY)
            .addStatement("$T joranConfigurator = new $T()", DefaultJoranConfigurator.class, DefaultJoranConfigurator.class)
            .addStatement("joranConfigurator.setContext(loggerContext)")
            .beginControlFlow("if (joranConfigurator.configure(loggerContext) == $T.DO_NOT_INVOKE_NEXT_IF_ANY)", Configurator.ExecutionStatus.class)
            .addStatement("return $T.DO_NOT_INVOKE_NEXT_IF_ANY", Configurator.ExecutionStatus.class)
            .endControlFlow()
            .endControlFlow();
    }

    /**
     * Mirrors Joran's {@code ConfigurationModelHandler}.
     */
    private void emitConfiguration(ConfigurationModel configuration) {
        String scan = requireLiteral(configuration.getScanStr());
        if (!isBlank(scan) && !FALSE.equalsIgnoreCase(scan.trim())) {
            throw unsupported("enables scan, which needs Joran to watch the file");
        }
        String debug = requireLiteral(configuration.getDebugStr());
        if (debug == null) {
            code.addStatement("$T logbackDebug = $T.getSystemProperty($S)", String.class, OptionHelper.class, DEBUG_PROPERTY);
        } else {
            code.addStatement("$T logbackDebug = $T.getSystemProperty($S, $S)", String.class, OptionHelper.class, DEBUG_PROPERTY, debug);
        }
        code.beginControlFlow("if (logbackDebug != null && !logbackDebug.trim().isEmpty() && !logbackDebug.equalsIgnoreCase($S) && !logbackDebug.equalsIgnoreCase($S))", FALSE, "null")
            .addStatement("$T.addOnConsoleListenerInstance(loggerContext, new $T())", StatusListenerConfigHelper.class, OnConsoleStatusListener.class)
            .endControlFlow();
        // Joran always sets it, and LoggerContext#reset does not restore the default
        String packagingData = requireLiteral(configuration.getPackagingDataStr());
        code.addStatement("loggerContext.setPackagingDataEnabled($L)", OptionHelper.toBoolean(packagingData, LoggerContext.DEFAULT_PACKAGING_DATA));
        code.addStatement("new $T(loggerContext).addGroovyPackages(loggerContext.getFrameworkPackages())", ContextUtil.class);
    }

    private void emitStatusListener(StatusListenerModel model) {
        Class<?> type = loadComponentClass(model.getClassName(), StatusListener.class);
        String variable = variableNameOf(model);
        boolean lifeCycle = LifeCycle.class.isAssignableFrom(type);
        emitNewInstance(type, variable);
        if (lifeCycle) {
            code.addStatement("boolean $LAdded = loggerContext.getStatusManager().add($L)", variable, variable);
        } else {
            code.addStatement("loggerContext.getStatusManager().add($L)", variable);
        }
        emitSetContext(type, variable);
        emitNestedComponents(model, type, variable);
        if (lifeCycle) {
            code.beginControlFlow("if ($LAdded)", variable);
            emitStart(variable);
            code.endControlFlow();
        }
    }

    private void emitLoggerContextListener(LoggerContextListenerModel model) {
        Class<?> type = loadComponentClass(model.getClassName(), LoggerContextListener.class);
        String variable = variableNameOf(model);
        emitNewInstance(type, variable);
        emitSetContext(type, variable);
        emitNestedComponents(model, type, variable);
        if (LifeCycle.class.isAssignableFrom(type)) {
            emitStart(variable);
        }
        code.addStatement("loggerContext.addListener($L)", variable);
    }

    private void emitAppender(AppenderModel model) {
        String name = model.getName();
        if (!referencedAppenders.contains(name)) {
            // Joran skips appenders which are not referenced
            return;
        }
        Class<?> type = loadComponentClass(model.getClassName(), Appender.class);
        String variable = variableNameOf(model);
        emitNewInstance(type, variable);
        code.addStatement("$L.setContext(loggerContext)", variable);
        code.addStatement("$L.setName($S)", variable, name);
        for (Model subModel : model.getSubModels()) {
            if (subModel.getClass() == ImplicitModel.class) {
                emitProperty((ImplicitModel) subModel, type, variable);
            } else if (subModel.getClass() == AppenderRefModel.class) {
                emitNestedAppenderRef((AppenderRefModel) subModel, name, type, variable);
            } else {
                throw unsupportedElement(subModel);
            }
        }
        emitStart(variable);
        appenderVariables.put(name, variable);
    }

    private void emitNestedAppenderRef(AppenderRefModel model, String appenderName, Class<?> appenderType, String appenderVariable) {
        if (!AppenderAttachable.class.isAssignableFrom(appenderType)) {
            throw unsupported("nests <appender-ref> in appender [" + appenderName + "], which is not an " + AppenderAttachable.class.getName());
        }
        String ref = requireDeclaredAppender(model.getRef());
        String refVariable = appenderVariables.get(ref);
        if (refVariable == null) {
            // Joran would defer the appender until the referenced one is started
            throw unsupported("references appender [" + ref + "] from appender [" + appenderName + "] before declaring it");
        }
        code.addStatement("$L.addAppender($L)", appenderVariable, refVariable);
    }

    /**
     * Mirrors Joran's {@code LoggerModelHandler}.
     */
    private void emitLogger(LoggerModel model) {
        String name = requireName(model.getName(), "a logger without a name");
        String variable = variableNameOf(model);
        code.addStatement("$T $L = loggerContext.getLogger($S)", Logger.class, variable, name);
        String level = requireLiteral(model.getLevel());
        if (!isBlank(level)) {
            if (INHERITED.equalsIgnoreCase(level) || NULL.equalsIgnoreCase(level)) {
                if (org.slf4j.Logger.ROOT_LOGGER_NAME.equalsIgnoreCase(name)) {
                    throw unsupported("sets the level of the root logger to " + level);
                }
                code.addStatement("$L.setLevel(null)", variable);
            } else {
                code.addStatement("$L.setLevel($T.$L)", variable, Level.class, Level.toLevel(level));
            }
        }
        String additivity = requireLiteral(model.getAdditivity());
        if (!isBlank(additivity)) {
            code.addStatement("$L.setAdditive($L)", variable, OptionHelper.toBoolean(additivity, true));
        }
        collectLoggerAppenderRefs(model, variable);
    }

    /**
     * Mirrors Joran's {@code RootLoggerModelHandler}.
     */
    private void emitRootLogger(RootLoggerModel model) {
        String variable = variableNameOf(model);
        code.addStatement("$T $L = loggerContext.getLogger($T.ROOT_LOGGER_NAME)", Logger.class, variable, Logger.class);
        String level = requireLiteral(model.getLevel());
        if (!isBlank(level)) {
            code.addStatement("$L.setLevel($T.$L)", variable, Level.class, Level.toLevel(level));
        }
        collectLoggerAppenderRefs(model, variable);
    }

    private void collectLoggerAppenderRefs(Model model, String loggerVariable) {
        Set<String> appenders = loggerAppenderRefs.computeIfAbsent(loggerVariable, k -> new LinkedHashSet<>());
        for (Model subModel : model.getSubModels()) {
            if (subModel.getClass() != AppenderRefModel.class) {
                throw unsupportedElement(subModel);
            }
            appenders.add(requireDeclaredAppender(((AppenderRefModel) subModel).getRef()));
        }
    }

    private void emitNestedComponents(Model model, Class<?> type, String variable) {
        for (Model subModel : model.getSubModels()) {
            if (subModel.getClass() != ImplicitModel.class) {
                throw unsupportedElement(subModel);
            }
            emitProperty((ImplicitModel) subModel, type, variable);
        }
    }

    /**
     * Mirrors Joran's {@code ImplicitModelHandler}: the aggregation type is computed from the parent's adder,
     * then setter, and the parameter type decides whether the property is a simple value or a component.
     */
    private void emitProperty(ImplicitModel model, Class<?> parentType, String parentVariable) {
        String tag = model.getTag();
        BeanDescription parent = beanDescriptionCache.getBeanDescription(parentType);
        Method adder = parent.getAdder(BeanUtil.toLowerCamelCase(capitalizeFirstLetter(tag)));
        Method method = adder != null ? adder : parent.getSetter(BeanUtil.toLowerCamelCase(tag));
        if (method == null || method.getParameterCount() != 1) {
            throw unsupportedProperty(tag, ", which " + parentType.getName() + " does not have");
        }
        Class<?> parameterType = method.getParameterTypes()[0];
        if (StringToObjectConverter.canBeBuiltFromSimpleString(parameterType)) {
            emitSimpleProperty(model, method, parentVariable);
        } else {
            if (adder != null && adder != parent.getAdder(BeanUtil.toLowerCamelCase(tag))) {
                throw unsupportedProperty(tag, ", whose adder cannot be resolved consistently");
            }
            emitComponentProperty(model, method, parentType, parentVariable);
        }
    }

    private void emitSimpleProperty(ImplicitModel model, Method method, String parentVariable) {
        String tag = model.getTag();
        if (!model.getSubModels().isEmpty()) {
            throw unsupported("nests elements in property [" + tag + "]");
        }
        String value = requireLiteral(model.getBodyText());
        if (value == null) {
            // Joran ignores properties without a value
            return;
        }
        Class<?> type = method.getParameterTypes()[0];
        Object converted;
        try {
            converted = StringToObjectConverter.convertArg(conversionContext, value, type);
        } catch (RuntimeException | LinkageError _) {
            converted = null;
        }
        if (converted == null) {
            throw unsupportedProperty(tag, " to [" + value + "], which cannot be converted to " + type.getName());
        }
        emitCall(parentVariable, method, valueOf(value, type, converted));
    }

    /**
     * Generates the expression {@link StringToObjectConverter#convertArg} evaluates to.
     */
    private CodeBlock valueOf(String value, Class<?> type, Object converted) {
        String trimmed = value.trim();
        if (type == String.class) {
            return CodeBlock.of("$S", trimmed);
        } else if (type == int.class || type == boolean.class) {
            return CodeBlock.of("$L", converted);
        } else if (type == long.class) {
            return CodeBlock.of("$LL", converted);
        } else if (type == float.class || type == double.class) {
            return CodeBlock.of("$T.valueOf($S)", type == float.class ? Float.class : Double.class, trimmed);
        } else if (type.isEnum() || followsTheValueOfConvention(type)) {
            return valueOfMethodCall(type, trimmed);
        } else if (Charset.class.isAssignableFrom(type)) {
            return charsetOf(value);
        }
        throw unsupported("sets a property of type " + type.getName() + ", which is not supported");
    }

    /**
     * Generates the call to the {@code valueOf} method of an enum, or of a class following the same convention.
     */
    private CodeBlock valueOfMethodCall(Class<?> type, String trimmed) {
        requireAccessible(type);
        Method valueOf = StringToObjectConverter.getValueOfMethod(type);
        if (valueOf == null || !type.isAssignableFrom(valueOf.getReturnType())) {
            throw unsupported("sets a property of type " + type.getName() + ", whose valueOf method does not return that type");
        }
        requireNoCheckedExceptions(valueOf);
        return CodeBlock.of("$T.valueOf($S)", type, trimmed);
    }

    /**
     * Mirrors {@code StringToObjectConverter}, which converts {@code null}, ignoring case, to the default charset.
     */
    private static CodeBlock charsetOf(String value) {
        if (NULL.equalsIgnoreCase(value)) {
            return CodeBlock.of("$T.defaultCharset()", Charset.class);
        }
        return CodeBlock.of("$T.forName($S)", Charset.class, value);
    }

    private void emitComponentProperty(ImplicitModel model, Method method, Class<?> parentType, String parentVariable) {
        String tag = model.getTag();
        Class<?> expectedType = method.getParameterTypes()[0];
        String className = requireLiteral(model.getClassName());
        Class<?> type = isBlank(className) ? inferComponentClass(parentType, tag, method) : loadClass(className);
        if (type == null) {
            throw unsupported("does not declare the class of <" + tag + ">, and it cannot be inferred");
        }
        if (!expectedType.isAssignableFrom(type)) {
            throw unsupported("uses " + type.getName() + " for <" + tag + ">, which is not a " + expectedType.getName());
        }
        requireInstantiable(type);
        String variable = variableNameOf(model);
        emitNewInstance(type, variable);
        emitSetContext(type, variable);
        emitNestedComponents(model, type, variable);
        emitSetParent(type, variable, parentType, parentVariable);
        if (LifeCycle.class.isAssignableFrom(type) && !isMarkedWithNoAutoStart(type)) {
            emitStart(variable);
        }
        emitCall(parentVariable, method, variable);
    }

    /**
     * Mirrors {@code AggregationAssessor#getClassNameViaImplicitRules}.
     */
    private @Nullable Class<?> inferComponentClass(Class<?> parentType, String tag, Method method) {
        Class<?> registered = defaultNestedComponentRegistry.findDefaultComponentType(parentType, tag);
        if (registered != null) {
            return registered;
        }
        DefaultClass defaultClass = method.getAnnotation(DefaultClass.class);
        if (defaultClass != null) {
            return defaultClass.value();
        }
        Class<?> parameterType = method.getParameterTypes()[0];
        return isInstantiable(parameterType) ? parameterType : null;
    }

    /**
     * Joran lets a nested component point to its parent through a {@code parent} property.
     */
    private void emitSetParent(Class<?> type, String variable, Class<?> parentType, String parentVariable) {
        BeanDescription description = beanDescriptionCache.getBeanDescription(type);
        if (description.getAdder(PARENT_PROPERTY) != null) {
            return;
        }
        Method setter = description.getSetter(PARENT_PROPERTY);
        if (setter == null || setter.getParameterCount() != 1 || StringToObjectConverter.canBeBuiltFromSimpleString(setter.getParameterTypes()[0])) {
            return;
        }
        if (!setter.getParameterTypes()[0].isAssignableFrom(parentType)) {
            throw unsupported("nests " + type.getName() + " in " + parentType.getName() + ", which is not a valid parent");
        }
        emitCall(variable, setter, parentVariable);
    }

    private void emitNewInstance(Class<?> type, String variable) {
        code.addStatement("$T $L = new $T()", type, variable, type);
    }

    private void emitStart(String variable) {
        code.addStatement("$L.start()", variable);
    }

    private void emitCall(String receiver, Method method, Object argument) {
        requireNoCheckedExceptions(method);
        code.addStatement("$L.$L($L)", receiver, method.getName(), argument);
    }

    private void emitSetContext(Class<?> type, String variable) {
        if (ContextAware.class.isAssignableFrom(type)) {
            code.addStatement("$L.setContext(loggerContext)", variable);
        }
    }

    private Class<?> loadComponentClass(@Nullable String className, Class<?> expectedType) {
        if (isBlank(className)) {
            throw unsupported("declares a " + expectedType.getSimpleName() + " without a class");
        }
        Class<?> type = loadClass(className);
        if (!expectedType.isAssignableFrom(type)) {
            throw unsupportedClass(className, "which is not a " + expectedType.getName());
        }
        requireInstantiable(type);
        return type;
    }

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className, false, Logback14GeneratorHelper.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError _) {
            throw unsupportedClass(className, "which cannot be loaded");
        }
    }

    private void requireInstantiable(Class<?> type) {
        if (!isInstantiable(type)) {
            throw unsupportedClass(type.getName(), "which cannot be instantiated from generated code");
        }
    }

    private static boolean isInstantiable(Class<?> type) {
        if (type.isInterface() || type.isArray() || type.isPrimitive() || java.lang.reflect.Modifier.isAbstract(type.getModifiers()) || !isAccessible(type)) {
            return false;
        }
        try {
            return !declaresCheckedExceptions(type.getConstructor());
        } catch (NoSuchMethodException _) {
            return false;
        }
    }

    /**
     * The generated {@code configure} method does not declare checked exceptions, unlike Joran, which calls
     * members reflectively.
     */
    private static void requireNoCheckedExceptions(Method method) {
        if (declaresCheckedExceptions(method)) {
            throw unsupported("calls " + method.getDeclaringClass().getName() + "#" + method.getName() + ", which declares checked exceptions");
        }
    }

    private static boolean declaresCheckedExceptions(Executable executable) {
        for (Class<?> exceptionType : executable.getExceptionTypes()) {
            if (!RuntimeException.class.isAssignableFrom(exceptionType) && !Error.class.isAssignableFrom(exceptionType)) {
                return true;
            }
        }
        return false;
    }

    private void requireAccessible(Class<?> type) {
        if (!isAccessible(type)) {
            throw unsupportedClass(type.getName(), "which is not public");
        }
    }

    private static boolean isAccessible(Class<?> type) {
        if (type.isAnonymousClass() || type.isLocalClass()) {
            return false;
        }
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            int modifiers = current.getModifiers();
            if (!java.lang.reflect.Modifier.isPublic(modifiers)) {
                return false;
            }
            if (current.getEnclosingClass() != null && !java.lang.reflect.Modifier.isStatic(modifiers)) {
                // inner classes need an enclosing instance
                return false;
            }
        }
        return true;
    }

    /**
     * Mirrors {@code StringToObjectConverter#followsTheValueOfConvention}, which is private before Logback 1.5.
     */
    private static boolean followsTheValueOfConvention(Class<?> type) {
        Method valueOf = StringToObjectConverter.getValueOfMethod(type);
        return valueOf != null && java.lang.reflect.Modifier.isStatic(valueOf.getModifiers());
    }

    /**
     * Mirrors {@code NoAutoStartUtil}, which also looks at interfaces and superclasses.
     */
    private static boolean isMarkedWithNoAutoStart(Class<?> type) {
        if (type.getAnnotation(NoAutoStart.class) != null) {
            return true;
        }
        for (Class<?> anInterface : type.getInterfaces()) {
            if (isMarkedWithNoAutoStart(anInterface)) {
                return true;
            }
        }
        Class<?> superclass = type.getSuperclass();
        return superclass != null && superclass != Object.class && isMarkedWithNoAutoStart(superclass);
    }

    private String variableNameOf(Model model) {
        return variableNames.computeIfAbsent(model, m -> {
            String base = baseVariableName(m);
            String name = base;
            int suffix = variableNames.size();
            while (!usedVariableNames.add(name)) {
                name = base + "_" + suffix++;
            }
            return name;
        });
    }

    private static String baseVariableName(Model model) {
        if (model instanceof RootLoggerModel) {
            return ROOT_LOGGER_VARIABLE;
        }
        String name;
        if (model instanceof NamedComponentModel namedComponentModel) {
            name = namedComponentModel.getName();
        } else if (model instanceof LoggerModel loggerModel) {
            name = loggerModel.getName();
        } else {
            name = model.getTag();
        }
        String identifier = name.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase(Locale.US);
        return SourceVersion.isName(identifier) ? identifier : "_" + identifier;
    }

    private String requireDeclaredAppender(@Nullable String ref) {
        String name = requireName(ref, "an appender-ref without a ref");
        if (!declaredAppenders.containsKey(name)) {
            throw unsupported("references appender [" + name + "], which is not declared");
        }
        return name;
    }

    private String requireName(@Nullable String name, String description) {
        String literal = requireLiteral(name);
        if (isBlank(literal)) {
            throw unsupported("declares " + description);
        }
        return literal;
    }

    /**
     * Joran passes most values through variable substitution, which also rewrites some values
     * without variables, such as {@code a:-b}. Only values which substitution leaves unchanged
     * are copied into the generated code.
     */
    private @Nullable String requireLiteral(@Nullable String value) {
        if (value == null) {
            return null;
        }
        if (value.contains(VARIABLE_SUBSTITUTION)) {
            throw unsupported("uses variable substitution in [" + value + "]");
        }
        String substituted;
        try {
            substituted = OptionHelper.substVars(value, analysisContext);
        } catch (ScanException | RuntimeException _) {
            substituted = null;
        }
        if (!value.equals(substituted)) {
            throw unsupported("uses [" + value + "], which Joran changes during variable substitution");
        }
        return value;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String capitalizeFirstLetter(String name) {
        return name.isEmpty() ? name : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    private static UnsupportedConfigurationException unsupportedElement(Model model) {
        return unsupported("contains <" + model.getTag() + ">, which cannot be converted");
    }

    private static UnsupportedConfigurationException unsupportedProperty(String tag, String reason) {
        return unsupported("sets property [" + tag + "]" + reason);
    }

    private static UnsupportedConfigurationException unsupportedClass(String className, String reason) {
        return unsupported("refers to class [" + className + "], " + reason);
    }

    private static UnsupportedConfigurationException unsupported(String reason) {
        return new UnsupportedConfigurationException(reason);
    }

    /**
     * Thrown when a Logback configuration cannot be converted faithfully.
     */
    static final class UnsupportedConfigurationException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private UnsupportedConfigurationException(String reason) {
            super(reason, null, false, false);
        }
    }

    /**
     * Builds the Joran model, and exposes Joran's rules for the default class of nested components.
     */
    private static final class ModelBuilder extends JoranConfigurator {
        private DefaultNestedComponentRegistry defaultNestedComponentRegistry() {
            var registry = new DefaultNestedComponentRegistry();
            addDefaultNestedComponentRegistryRules(registry);
            return registry;
        }
    }
}
