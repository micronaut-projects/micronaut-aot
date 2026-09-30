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
package io.micronaut.aot.logback.frontend;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.model.ConfigurationModel;
import ch.qos.logback.classic.model.LoggerModel;
import ch.qos.logback.classic.model.RootLoggerModel;
import ch.qos.logback.classic.model.processor.LogbackClassicDefaultNestedComponentRules;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.joran.event.SaxEventRecorder;
import ch.qos.logback.core.joran.spi.DefaultNestedComponentRegistry;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.joran.spi.NoAutoStartUtil;
import ch.qos.logback.core.joran.util.AggregationAssessor;
import ch.qos.logback.core.joran.util.StringToObjectConverter;
import ch.qos.logback.core.joran.util.beans.BeanDescriptionCache;
import ch.qos.logback.core.model.AppenderModel;
import ch.qos.logback.core.model.AppenderRefModel;
import ch.qos.logback.core.model.ImplicitModel;
import ch.qos.logback.core.model.Model;
import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.ScanException;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusListener;
import ch.qos.logback.core.util.AggregationType;
import ch.qos.logback.core.util.OptionHelper;

import java.io.ByteArrayInputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads a {@code logback.xml} with Logback's own SAX recorder and model builder, and reduces it to a description
 * made of {@code java.base} types only, from which the precompiler generates a {@code Configurator}.
 *
 * <p>It runs in an isolated class loader whose only other entries are the application's own logback-classic,
 * logback-core and slf4j-api jars, and whose parent is the platform class loader: no application class, no other
 * dependency and no class of the precompiler is visible to it. It never processes the model, never starts an appender
 * and never opens a file or a stream. It instantiates only the {@code ch.qos.logback.*} appender classes the
 * configuration names (to ask Logback which of their properties are simple and how a value converts), the
 * {@code PatternLayoutEncoder}, and a scratch {@code PatternLayout} per pattern, whose start compiles the pattern
 * with Logback's own converter table so that a pattern Logback reports a problem with is rejected.</p>
 *
 * <p>Everything outside a literal subset is rejected: the result then names the element, and the configuration is
 * left to Joran at startup. So is what Joran decides in phases this front end does not run or in ways the generated
 * code does not reproduce: two appenders that share a {@code <file>} (Joran's {@code FileCollisionAnalyser} skips
 * the later one), a property element without text (Joran makes no setter call for it) and a {@code <name>} element
 * inside an appender (Joran then keeps the appender under that name, not the one its loggers reference). The result is either {@code {"irVersion": 1, "rejection": reason}} or
 * {@code {"irVersion": 1, "operations": [...], "appenders": n, "patterns": n}}. The operations are in the order
 * Joran's second processing phase applies them: an appender is created and started when the traversal reaches it,
 * and a logger once every appender it references has been started.</p>
 */
public final class LogbackFrontend implements Function<byte[], Map<String, Object>> {

    /** The version of the description this front end returns; the precompiler rejects any other. */
    public static final int IR_VERSION = 1;

    private static final String ENCODER = "encoder";
    private static final String PATTERN = "pattern";
    private static final String PARENT = "parent";
    private static final String PATTERN_LAYOUT_ENCODER = "ch.qos.logback.classic.encoder.PatternLayoutEncoder";
    private static final String LOGBACK_PACKAGE = "ch.qos.logback.";
    private static final List<String> LEVELS = List.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL");
    /** The elements Joran's {@code FileCollisionAnalyser} compares across appenders. */
    private static final List<String> COLLISION_TAGS = List.of("file", "fileNamePattern");

    @Override
    public Map<String, Object> apply(byte[] xml) {
        LoggerContext scratch = new LoggerContext();
        scratch.setName("micronaut-aot-logback-precompiler");
        Problems problems = new Problems();
        scratch.getStatusManager().add(problems);
        try {
            requireMembers();
            return new Reader(scratch, problems).read(xml);
        } catch (Rejection e) {
            return rejection(e.getMessage());
        } catch (JoranException e) {
            return rejection("Logback cannot read it: " + e.getMessage());
        } catch (LinkageError e) {
            return rejection("the Logback member " + e.getMessage() + " the precompiler relies on is missing ("
                    + e.getClass().getSimpleName() + ")");
        } finally {
            scratch.stop();
        }
    }

    /**
     * Looks up, in the Logback jars this front end runs against, every member the generated configurator and
     * {@code JoranFallback} call whatever the configuration, so that a Logback release that changed one is rejected
     * here instead of failing when the application starts. The members a configuration adds, its appenders'
     * setters, are looked up as they are read.
     *
     * @throws Rejection if a member is missing
     */
    static void requireMembers() {
        try {
            ClassLoader loader = LogbackFrontend.class.getClassLoader();
            Class<?> context = Class.forName("ch.qos.logback.core.Context", false, loader);
            Class<?> loggerContext = Class.forName("ch.qos.logback.classic.LoggerContext", false, loader);
            Class<?> logger = Class.forName("ch.qos.logback.classic.Logger", false, loader);
            Class<?> level = Class.forName("ch.qos.logback.classic.Level", false, loader);
            Class<?> appender = Class.forName("ch.qos.logback.core.Appender", false, loader);
            Class<?> status = Class.forName("ch.qos.logback.core.status.Status", false, loader);
            Class<?> statusManager = Class.forName("ch.qos.logback.core.status.StatusManager", false, loader);
            loggerContext.getMethod("getLogger", String.class);
            loggerContext.getMethod("setPackagingDataEnabled", boolean.class);
            loggerContext.getMethod("getFrameworkPackages");
            loggerContext.getMethod("getStatusManager");
            Class<?> contextUtil = Class.forName("ch.qos.logback.core.util.ContextUtil", false, loader);
            contextUtil.getConstructor(context);
            contextUtil.getMethod("addGroovyPackages", List.class);
            logger.getMethod("setLevel", level);
            logger.getMethod("setAdditive", boolean.class);
            logger.getMethod("addAppender", appender);
            for (String name : LEVELS) {
                level.getField(name);
            }
            appender.getMethod("setContext", context);
            appender.getMethod("setName", String.class);
            Class.forName("ch.qos.logback.core.spi.LifeCycle", false, loader).getMethod("start");
            Class.forName("ch.qos.logback.core.spi.ContextAware", false, loader).getMethod("setContext", context);
            Class.forName("ch.qos.logback.core.spi.ContextAwareBase", false, loader).getConstructor();
            Class.forName("ch.qos.logback.core.status.WarnStatus", false, loader)
                    .getConstructor(String.class, Object.class);
            statusManager.getMethod("add", status);
            Class.forName("ch.qos.logback.classic.spi.Configurator$ExecutionStatus", false, loader)
                    .getField("DO_NOT_INVOKE_NEXT_IF_ANY");
            Class<?> joran = Class.forName("ch.qos.logback.classic.util.DefaultJoranConfigurator", false, loader);
            joran.getMethod("configure", loggerContext);
            joran.getMethod("configureByResource", URL.class);
        } catch (ReflectiveOperationException e) {
            throw new Rejection("the Logback member " + e.getMessage() + " the generated code calls is missing");
        }
    }

    private static Map<String, Object> rejection(String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("irVersion", IR_VERSION);
        result.put("rejection", reason);
        return result;
    }

    /**
     * Collects every status of level {@code WARN} or above that Logback reports while the file is read and its
     * patterns are compiled.
     */
    private static final class Problems implements StatusListener {

        private final List<String> messages = new ArrayList<>();

        @Override
        public void addStatusEvent(Status status) {
            if (status.getLevel() >= Status.WARN) {
                Throwable cause = status.getThrowable();
                messages.add(status.getMessage() + (cause == null ? "" : " (" + cause + ")"));
            }
        }

        @Override
        public boolean isResetResistant() {
            return false;
        }

        void check(String when) {
            if (!messages.isEmpty()) {
                throw new Rejection("Logback reported a problem while " + when + ": " + messages.get(0));
            }
        }
    }

    /** Why the configuration is outside the subset, phrased to follow "because". */
    private static final class Rejection extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Rejection(String reason) {
            super(reason, null, false, false);
        }
    }

    /** One read of one file. */
    private static final class Reader extends ContextAwareBase {

        private final LoggerContext scratch;
        private final Problems problems;
        private final BeanDescriptionCache beans;
        /** Logback-classic's default nested component rules: the class of an {@code <encoder>} naming none. */
        private final DefaultNestedComponentRegistry registry = new DefaultNestedComponentRegistry();
        private final Map<String, AppenderModel> declared = new LinkedHashMap<>();
        private final Set<String> referenced = new HashSet<>();
        private final Set<String> patterns = new LinkedHashSet<>();
        /** Per collision tag and value, the appender that declared it first. */
        private final Map<String, String> files = new HashMap<>();

        Reader(LoggerContext scratch, Problems problems) {
            this.scratch = scratch;
            this.problems = problems;
            this.beans = new BeanDescriptionCache(scratch);
            setContext(scratch);
            LogbackClassicDefaultNestedComponentRules.addDefaultNestedComponentRegistryRules(registry);
        }

        Map<String, Object> read(byte[] xml) throws JoranException {
            SaxEventRecorder recorder = new SaxEventRecorder(scratch);
            recorder.recordEvents(new ByteArrayInputStream(xml));
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(scratch);
            Model top = configurator.buildModelFromSaxEventList(recorder.getSaxEventList());
            problems.check("reading it");
            if (!(top instanceof ConfigurationModel configuration)) {
                throw new Rejection("its top element is not <configuration>");
            }
            checkConfiguration(configuration);

            List<Model> phaseTwo = new ArrayList<>();
            for (Model child : configuration.getSubModels()) {
                if (child.getClass() == AppenderModel.class) {
                    AppenderModel appender = (AppenderModel) child;
                    String name = literal(appender.getName(), "the name of an <appender>");
                    if (declared.putIfAbsent(name, appender) != null) {
                        throw new Rejection("two <appender> elements are named '" + name + "'");
                    }
                    rejectFileCollisions(appender, name);
                } else if (child.getClass() == LoggerModel.class || child.getClass() == RootLoggerModel.class) {
                    checkLogger(child);
                } else {
                    throw new Rejection(element(child) + " is not supported");
                }
                requireNoText(child);
                phaseTwo.add(child);
            }

            List<Object> operations = new ArrayList<>();
            int appenders = 0;
            Set<String> started = new HashSet<>();
            Set<Model> handled = new HashSet<>();
            boolean progress = true;
            while (progress) {
                progress = false;
                for (Model model : phaseTwo) {
                    if (handled.contains(model)) {
                        continue;
                    }
                    if (model instanceof AppenderModel appender) {
                        handled.add(model);
                        progress = true;
                        String name = appender.getName();
                        if (referenced.contains(name)) {
                            operations.add(appender(appender));
                            appenders++;
                            started.add(name);
                        } else {
                            Map<String, Object> skip = new LinkedHashMap<>();
                            skip.put("op", "skip");
                            skip.put("name", name);
                            operations.add(skip);
                        }
                    } else if (started.containsAll(references(model))) {
                        handled.add(model);
                        progress = true;
                        operations.add(logger(model));
                    }
                }
            }
            if (handled.size() != phaseTwo.size()) {
                throw new Rejection("the appender references cannot be resolved in order");
            }
            for (String pattern : patterns) {
                compile(pattern);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("irVersion", IR_VERSION);
            result.put("operations", operations);
            result.put("appenders", appenders);
            result.put("patterns", patterns.size());
            return result;
        }

        private void checkConfiguration(ConfigurationModel configuration) {
            requireNoText(configuration);
            requireFalseOrAbsent(configuration.getDebugStr(), "debug");
            requireFalseOrAbsent(configuration.getScanStr(), "scan");
            requireFalseOrAbsent(configuration.getPackagingDataStr(), "packagingData");
            if (configuration.getScanPeriodStr() != null) {
                throw new Rejection("<configuration> sets scanPeriod");
            }
        }

        /**
         * Rejects what Joran's {@code FileCollisionAnalyser} acts on in its dependency-analysis phase, which this
         * front end does not run: a {@code <file>} or {@code <fileNamePattern>} with the value an earlier appender
         * has, referenced or not. Joran reports a collision and never creates the later appender. The check is
         * wider than Joran's, which only looks at {@code FileAppender} and {@code RollingFileAppender} by name and
         * at the first such element of each: a false rejection only costs the optimisation.
         */
        private void rejectFileCollisions(AppenderModel appender, String name) {
            String what = "<appender name=\"" + name + "\">";
            for (Model child : appender.getSubModels()) {
                rejectFileCollision(child, name, what);
                for (Model grandchild : child.getSubModels()) {
                    rejectFileCollision(grandchild, name, what);
                }
            }
        }

        private void rejectFileCollision(Model model, String appender, String what) {
            for (String tag : COLLISION_TAGS) {
                if (!(model instanceof ImplicitModel) || !tag.equalsIgnoreCase(model.getTag())) {
                    continue;
                }
                String where = "<" + model.getTag() + "> inside " + what;
                String value = literal(body(model, where), where).trim();
                String first = files.putIfAbsent(tag + '\n' + value, appender);
                if (first != null && !first.equals(appender)) {
                    throw new Rejection(what + " has the same <" + tag + "> as <appender name=\"" + first
                            + "\">, which Joran reports as a collision: " + value);
                }
            }
        }

        private static void requireFalseOrAbsent(String value, String attribute) {
            if (value != null && !"false".equalsIgnoreCase(value)) {
                throw new Rejection("<configuration> sets " + attribute + "=\"" + value + "\"");
            }
        }

        private void checkLogger(Model logger) {
            String what = logger instanceof LoggerModel named
                    ? "<logger name=\"" + named.getName() + "\">" : "<root>";
            for (Model child : logger.getSubModels()) {
                if (child.getClass() != AppenderRefModel.class) {
                    throw new Rejection(element(child) + " inside " + what + " is not supported");
                }
                requireNoText(child);
                requireNoChildren(child);
                referenced.add(literal(((AppenderRefModel) child).getRef(), "an <appender-ref> in " + what));
            }
        }

        private static List<String> references(Model logger) {
            List<String> refs = new ArrayList<>();
            for (Model child : logger.getSubModels()) {
                refs.add(((AppenderRefModel) child).getRef());
            }
            return refs;
        }

        private Map<String, Object> logger(Model model) {
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("op", "logger");
            String level;
            if (model instanceof LoggerModel logger) {
                String name = literal(logger.getName(), "the name of a <logger>");
                if (name.isBlank() || "ROOT".equalsIgnoreCase(name)) {
                    throw new Rejection("a <logger> is named '" + name + "'");
                }
                operation.put("name", name);
                level = level(logger.getLevel(), "<logger name=\"" + name + "\">", true);
                String additivity = logger.getAdditivity();
                if (additivity != null && !OptionHelper.isNullOrEmptyOrAllSpaces(literal(additivity, "additivity"))) {
                    String trimmed = additivity.trim();
                    if (!"true".equalsIgnoreCase(trimmed) && !"false".equalsIgnoreCase(trimmed)) {
                        throw new Rejection("<logger name=\"" + name + "\"> has additivity=\"" + additivity + "\"");
                    }
                    operation.put("additivity", Boolean.valueOf("true".equalsIgnoreCase(trimmed)));
                }
            } else {
                operation.put("name", "ROOT");
                level = level(((RootLoggerModel) model).getLevel(), "<root>", false);
            }
            if (level != null) {
                operation.put("level", level);
            }
            operation.put("appenders", references(model));
            return operation;
        }

        /**
         * Reads a level as {@code LoggerModelHandler} and {@code RootLoggerModelHandler} do, rejecting what
         * {@code Level.toLevel} would silently turn into {@code DEBUG}.
         *
         * @return the level's name, {@code NULL} for {@code setLevel(null)}, or {@code null} when none is set
         */
        private String level(String value, String what, boolean nullable) {
            if (value == null) {
                return null;
            }
            String text = literal(value, "the level of " + what);
            if (OptionHelper.isNullOrEmptyOrAllSpaces(text)) {
                return null;
            }
            if ("INHERITED".equalsIgnoreCase(text) || "NULL".equalsIgnoreCase(text)) {
                if (!nullable) {
                    throw new Rejection(what + " sets its level to " + text);
                }
                return "NULL";
            }
            String trimmed = text.trim().toUpperCase(Locale.ROOT);
            if (!LEVELS.contains(trimmed)) {
                throw new Rejection(what + " sets the unknown level '" + text + "'");
            }
            return trimmed;
        }

        private Map<String, Object> appender(AppenderModel model) {
            String name = model.getName();
            String what = "<appender name=\"" + name + "\">";
            Class<?> type = logbackClass(literal(model.getClassName(), "the class of " + what), what);
            if (!Appender.class.isAssignableFrom(type)) {
                throw new Rejection(what + " names " + type.getName() + ", which is not an Appender");
            }
            Object appender = instantiate(type, what);
            AggregationAssessor assessor = new AggregationAssessor(beans, type);
            assessor.setContext(scratch);
            List<Object> steps = new ArrayList<>();
            boolean encoder = false;
            for (Model child : model.getSubModels()) {
                if (child.getClass() != ImplicitModel.class) {
                    throw new Rejection(element(child) + " inside " + what + " is not supported");
                }
                ImplicitModel property = (ImplicitModel) child;
                String tag = property.getTag();
                AggregationType aggregation = assessor.computeAggregationType(tag);
                if (aggregation == AggregationType.AS_BASIC_PROPERTY) {
                    steps.add(basicProperty(assessor, property, what));
                } else if (ENCODER.equals(tag) && aggregation == AggregationType.AS_COMPLEX_PROPERTY && !encoder) {
                    encoder = true;
                    steps.add(encoder(assessor, property, what));
                } else {
                    throw new Rejection("<" + tag + "> inside " + what + " is not supported");
                }
            }
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("op", "appender");
            operation.put("name", name);
            operation.put("className", type.getName());
            operation.put("steps", steps);
            operation.put("start", NoAutoStartUtil.notMarkedWithNoAutoStart(appender));
            return operation;
        }

        private Map<String, Object> basicProperty(AggregationAssessor assessor, ImplicitModel property,
                                                  String what) {
            String tag = property.getTag();
            if (property.getClassName() != null) {
                throw new Rejection("<" + tag + "> inside " + what + " names a class");
            }
            requireNoChildren(property);
            String where = "<" + tag + "> inside " + what;
            Method setter = assessor.findSetterMethod(tag);
            if (setter != null && "setName".equals(setter.getName())) {
                // Joran marks the appender started, and keeps it, under the name it has once its properties are
                // set: the loggers that reference the name of the attribute then never get it.
                throw new Rejection(where + " renames the appender");
            }
            Class<?> parameter = setter == null || setter.getParameterCount() != 1 ? null
                    : setter.getParameterTypes()[0];
            if (parameter != String.class && parameter != boolean.class && parameter != int.class) {
                throw new Rejection(where + " is not a String, boolean or int property");
            }
            Object value = convert(literal(body(property, where), where), parameter, where);
            Map<String, Object> step = setterStep("property", setter);
            step.put("value", value);
            return step;
        }

        private Map<String, Object> encoder(AggregationAssessor assessor, ImplicitModel model, String what) {
            String where = "the <encoder> of " + what;
            requireNoText(model);
            String className = model.getClassName();
            Class<?> type;
            if (className == null) {
                type = assessor.getClassNameViaImplicitRules(ENCODER, AggregationType.AS_COMPLEX_PROPERTY,
                        registry);
            } else {
                type = logbackClass(literal(className, "the class of " + where), where);
            }
            if (type == null || !PATTERN_LAYOUT_ENCODER.equals(type.getName())) {
                throw new Rejection(where + " is " + (type == null ? "of no known class" : type.getName())
                        + ", not a PatternLayoutEncoder");
            }
            Method setter = assessor.findSetterMethod(ENCODER);
            if (setter == null || setter.getParameterCount() != 1
                    || !setter.getParameterTypes()[0].isAssignableFrom(type)) {
                throw new Rejection(what + " has no encoder setter that takes a PatternLayoutEncoder");
            }
            List<Model> children = model.getSubModels();
            if (children.size() != 1 || children.get(0).getClass() != ImplicitModel.class
                    || !PATTERN.equals(children.get(0).getTag())) {
                throw new Rejection(where + " has other content than exactly one <pattern>");
            }
            ImplicitModel patternModel = (ImplicitModel) children.get(0);
            if (patternModel.getClassName() != null) {
                throw new Rejection("the <pattern> of " + where + " names a class");
            }
            requireNoChildren(patternModel);
            String pattern = (String) convert(literal(body(patternModel, "the <pattern> of " + where),
                    "the <pattern> of " + where), String.class, "the <pattern> of " + where);
            patterns.add(pattern);

            Object encoder = instantiate(type, where);
            AggregationAssessor encoderAssessor = new AggregationAssessor(beans, type);
            encoderAssessor.setContext(scratch);
            Method patternSetter = encoderAssessor.findSetterMethod(PATTERN);
            if (patternSetter == null || patternSetter.getParameterCount() != 1
                    || patternSetter.getParameterTypes()[0] != String.class) {
                throw new Rejection(where + " has no pattern setter");
            }
            Map<String, Object> step = setterStep(ENCODER, setter);
            step.put("className", type.getName());
            step.put("pattern", pattern);
            step.put("patternMethod", patternSetter.getName());
            step.put("patternDescriptor", descriptor(patternSetter));
            if (encoderAssessor.computeAggregationType(PARENT) == AggregationType.AS_COMPLEX_PROPERTY) {
                Method parent = encoderAssessor.findSetterMethod(PARENT);
                if (parent == null || parent.getParameterCount() != 1) {
                    throw new Rejection(where + " has a parent property without a setter");
                }
                step.put("parentMethod", parent.getName());
                step.put("parentDescriptor", descriptor(parent));
            }
            step.put("start", NoAutoStartUtil.shouldBeStarted(encoder));
            return step;
        }

        private static Map<String, Object> setterStep(String kind, Method setter) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step", kind);
            step.put("method", setter.getName());
            step.put("descriptor", descriptor(setter));
            return step;
        }

        private static String descriptor(Method method) {
            return MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                    .toMethodDescriptorString();
        }

        private Object convert(String text, Class<?> type, String what) {
            Object value;
            try {
                value = StringToObjectConverter.convertArg(this, text, type);
            } catch (RuntimeException e) {
                throw new Rejection(what + " cannot be converted to " + type.getName() + ": " + e.getMessage());
            }
            if (value == null) {
                throw new Rejection(what + " cannot be converted to " + type.getName());
            }
            return value;
        }

        private void compile(String pattern) {
            PatternLayout layout = new PatternLayout();
            layout.setContext(scratch);
            layout.setPattern(pattern);
            layout.start();
            problems.check("compiling the pattern '" + pattern + "'");
            if (!layout.isStarted()) {
                throw new Rejection("the pattern '" + pattern + "' does not compile");
            }
            layout.stop();
        }

        private Class<?> logbackClass(String name, String what) {
            if (!name.startsWith(LOGBACK_PACKAGE)) {
                throw new Rejection(what + " names " + name + ", which is not a ch.qos.logback class");
            }
            Class<?> type;
            try {
                type = Class.forName(name, false, LogbackFrontend.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new Rejection(what + " names " + name + ", which Logback does not have");
            }
            if (!Modifier.isPublic(type.getModifiers()) || Modifier.isAbstract(type.getModifiers())
                    || type.isInterface()) {
                throw new Rejection(what + " names " + name + ", which is not a public concrete class");
            }
            return type;
        }

        private static Object instantiate(Class<?> type, String what) {
            try {
                Constructor<?> constructor = type.getConstructor();
                return constructor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new Rejection(what + " cannot be created with a public no-argument constructor: " + e);
            }
        }

        /** The value Joran would use: after variable substitution, which must change nothing. */
        private String literal(String value, String what) {
            if (value == null) {
                throw new Rejection(what + " is missing");
            }
            if (value.contains("${")) {
                throw new Rejection(what + " uses a variable: " + value);
            }
            String substituted;
            try {
                substituted = OptionHelper.substVars(value, scratch);
            } catch (ScanException | IllegalArgumentException e) {
                throw new Rejection(what + " cannot be substituted: " + e.getMessage());
            }
            if (!value.equals(substituted)) {
                throw new Rejection(what + " changes under variable substitution: " + value);
            }
            return value;
        }

        /**
         * The text of a property element. Joran makes no setter call for an element without text
         * ({@code PropertySetter.setProperty} returns on a {@code null} value), which a setter call with an empty
         * string does not reproduce, so such an element is rejected.
         */
        private static String body(Model model, String what) {
            String body = model.getBodyText();
            if (body == null || body.isBlank()) {
                throw new Rejection(what + " is empty");
            }
            return body;
        }

        private static void requireNoText(Model model) {
            if (model.getBodyText() != null && !model.getBodyText().isBlank()) {
                throw new Rejection(element(model) + " contains text");
            }
        }

        private static void requireNoChildren(Model model) {
            if (!model.getSubModels().isEmpty()) {
                throw new Rejection(element(model) + " contains " + element(model.getSubModels().get(0)));
            }
        }

        private static String element(Model model) {
            return "<" + model.getTag() + "> (line " + model.getLineNumber() + ")";
        }
    }
}
