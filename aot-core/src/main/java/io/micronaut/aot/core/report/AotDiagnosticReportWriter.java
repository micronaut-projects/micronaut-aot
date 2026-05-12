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
package io.micronaut.aot.core.report;

import com.squareup.javapoet.JavaFile;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.Option;
import io.micronaut.aot.core.Runtime;
import io.micronaut.aot.core.config.SourceGeneratorSelection;
import io.micronaut.aot.core.context.DefaultSourceGenerationContext;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Writes the structured Micronaut AOT diagnostics report.
 */
@Internal
public final class AotDiagnosticReportWriter {
    /**
     * The fixed JSON diagnostics report file name.
     */
    public static final String JSON_REPORT_FILE_NAME = "micronaut-aot-report.json";
    /**
     * The fixed HTML diagnostics report file name.
     */
    public static final String HTML_REPORT_FILE_NAME = "micronaut-aot-report.html";
    /**
     * Backward-compatible alias for the JSON diagnostics report file name.
     */
    public static final String REPORT_FILE_NAME = JSON_REPORT_FILE_NAME;
    private static final int SCHEMA_VERSION = 1;
    private static final String CLASS_NAME = "className";
    private static final String HTML_TABLE_START = "  <table>\n";
    private static final String HTML_TABLE_END = "  </table>\n";
    private static final String HTML_ROW_START = "    <tr>";
    private static final String HTML_ROW_END = "</tr>\n";

    private AotDiagnosticReportWriter() {
    }

    public static Path write(Path outputDirectory,
                             Runtime runtime,
                             String packageName,
                             Set<String> activeEnvironments,
                             List<SourceGeneratorSelection> sourceGenerators,
                             DefaultSourceGenerationContext context,
                             @Nullable String micronautVersion) {
        try {
            Files.createDirectories(outputDirectory);
            Path report = outputDirectory.resolve(JSON_REPORT_FILE_NAME);
            Files.writeString(
                report,
                toJson(runtime, packageName, activeEnvironments, sourceGenerators, context, micronautVersion),
                StandardCharsets.UTF_8
            );
            return report;
        } catch (IOException e) {
            throw new RuntimeException("Unable to write AOT diagnostics report to " + outputDirectory, e);
        }
    }

    public static Path writeHtml(Path outputDirectory,
                                 Runtime runtime,
                                 String packageName,
                                 Set<String> activeEnvironments,
                                 List<SourceGeneratorSelection> sourceGenerators,
                                 DefaultSourceGenerationContext context,
                                 @Nullable String micronautVersion) {
        try {
            Files.createDirectories(outputDirectory);
            Path report = outputDirectory.resolve(HTML_REPORT_FILE_NAME);
            Files.writeString(
                report,
                toHtml(runtime, packageName, activeEnvironments, sourceGenerators, context, micronautVersion),
                StandardCharsets.UTF_8
            );
            return report;
        } catch (IOException e) {
            throw new RuntimeException("Unable to write AOT diagnostics HTML report to " + outputDirectory, e);
        }
    }

    public static String toJson(Runtime runtime,
                                String packageName,
                                Set<String> activeEnvironments,
                                List<SourceGeneratorSelection> sourceGenerators,
                                DefaultSourceGenerationContext context,
                                @Nullable String micronautVersion) {
        var json = new StringBuilder(4096);
        json.append("{\n");
        appendNumber(json, 1, "schemaVersion", SCHEMA_VERSION, true);
        appendString(json, 1, "generatedAt", Instant.now().toString(), true);
        appendString(json, 1, "micronautVersion", micronautVersion, true);
        appendString(json, 1, "runtime", runtime.displayName(), true);
        appendString(json, 1, "packageName", packageName, true);
        appendStringArray(json, 1, "activeEnvironments", new TreeSet<>(activeEnvironments), true);
        appendOptimizers(json, sourceGenerators, context);
        appendGeneratedSources(json, context.getGeneratedJavaFiles());
        appendStringArray(json, 1, "generatedResources", context.getGeneratedResources(), true);
        appendStringArray(json, 1, "filteredResources", context.getExcludedResources(), true);
        appendExcludedServices(json, context.getExcludedServiceImplementations());
        appendStringArray(json, 1, "buildTimeInitClasses", new TreeSet<>(context.getBuildTimeInitClasses()), true);
        appendDiagnostics(json, context.getDiagnostics());
        json.append("}\n");
        return json.toString();
    }

    public static String toHtml(Runtime runtime,
                                String packageName,
                                Set<String> activeEnvironments,
                                List<SourceGeneratorSelection> sourceGenerators,
                                DefaultSourceGenerationContext context,
                                @Nullable String micronautVersion) {
        var html = new StringBuilder(8192);
        html.append("<!doctype html>\n");
        html.append("<html lang=\"en\">\n<head>\n");
        html.append("  <meta charset=\"utf-8\">\n");
        html.append("  <title>Micronaut AOT Diagnostics Report</title>\n");
        html.append("  <style>");
        html.append("body{font-family:system-ui,sans-serif;margin:2rem;line-height:1.45;color:#1f2933}");
        html.append("table{border-collapse:collapse;width:100%;margin:1rem 0 2rem}");
        html.append("th,td{border:1px solid #d9e2ec;padding:.45rem;text-align:left;vertical-align:top}");
        html.append("th{background:#f0f4f8}");
        html.append("code{background:#f0f4f8;padding:.1rem .25rem;border-radius:3px}");
        html.append(".muted{color:#52606d}");
        html.append("</style>\n</head>\n<body>\n");
        html.append("  <h1>Micronaut AOT Diagnostics Report</h1>\n");
        html.append("  <p class=\"muted\">Configuration values are not included. Optimizer options show only keys and redaction state.</p>\n");
        html.append("  <h2>Summary</h2>\n");
        html.append(HTML_TABLE_START);
        appendHtmlRow(html, "Schema version", String.valueOf(SCHEMA_VERSION));
        appendHtmlRow(html, "Generated at", Instant.now().toString());
        appendHtmlRow(html, "Micronaut version", micronautVersion);
        appendHtmlRow(html, "Runtime", runtime.displayName());
        appendHtmlRow(html, "Package", packageName);
        appendHtmlRow(html, "Active environments", String.join(", ", new TreeSet<>(activeEnvironments)));
        html.append(HTML_TABLE_END);
        appendHtmlOptimizers(html, sourceGenerators, context);
        appendHtmlGeneratedSources(html, context.getGeneratedJavaFiles());
        appendHtmlList(html, "Generated Resources", context.getGeneratedResources());
        appendHtmlList(html, "Filtered Resources", context.getExcludedResources());
        appendHtmlExcludedServices(html, context.getExcludedServiceImplementations());
        appendHtmlList(html, "Build-Time Initialization Classes", new TreeSet<>(context.getBuildTimeInitClasses()));
        appendHtmlDiagnostics(html, context.getDiagnostics());
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private static void appendOptimizers(StringBuilder json,
                                         List<SourceGeneratorSelection> sourceGenerators,
                                         DefaultSourceGenerationContext context) {
        indent(json, 1).append("\"optimizers\": [\n");
        for (int i = 0; i < sourceGenerators.size(); i++) {
            SourceGeneratorSelection selection = sourceGenerators.get(i);
            AOTModule module = selection.getModule();
            indent(json, 2).append("{\n");
            appendString(json, 3, "id", module == null ? null : module.id(), true);
            appendString(json, 3, CLASS_NAME, selection.getGenerator().getClass().getName(), true);
            appendString(json, 3, "description", module == null ? null : module.description(), true);
            appendBoolean(json, 3, "enabled", selection.isEnabled(), true);
            appendString(json, 3, "disabledReason", selection.getDisabledReason(), true);
            appendOptions(json, module == null ? new Option[0] : module.options(), context);
            appendStringArray(json, 3, "dependencies", module == null ? List.of() : Arrays.asList(module.dependencies()), true);
            appendStringArray(json, 3, "enabledRuntimes", module == null
                ? List.of()
                : Arrays.stream(module.enabledOn()).map(Runtime::displayName).toList(), false);
            indent(json, 2).append("}");
            if (i < sourceGenerators.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        indent(json, 1).append("],\n");
    }

    private static void appendOptions(StringBuilder json, Option[] options, DefaultSourceGenerationContext context) {
        indent(json, 3).append("\"options\": [\n");
        for (int i = 0; i < options.length; i++) {
            Option option = options[i];
            boolean configured = context.getConfiguration().containsKey(option.key());
            indent(json, 4).append("{\n");
            appendString(json, 5, "key", option.key(), true);
            appendBoolean(json, 5, "configured", configured, true);
            appendBoolean(json, 5, "redacted", configured, false);
            indent(json, 4).append("}");
            if (i < options.length - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        indent(json, 3).append("],\n");
    }

    private static void appendGeneratedSources(StringBuilder json, List<JavaFile> generatedJavaFiles) {
        indent(json, 1).append("\"generatedSources\": [\n");
        for (int i = 0; i < generatedJavaFiles.size(); i++) {
            JavaFile javaFile = generatedJavaFiles.get(i);
            String className = javaFile.packageName + "." + javaFile.typeSpec.name;
            indent(json, 2).append("{\n");
            appendString(json, 3, CLASS_NAME, className, true);
            appendString(json, 3, "path", className.replace('.', '/') + ".java", false);
            indent(json, 2).append("}");
            if (i < generatedJavaFiles.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        indent(json, 1).append("],\n");
    }

    private static void appendExcludedServices(StringBuilder json, Map<String, String> excludedServiceImplementations) {
        indent(json, 1).append("\"excludedServiceImplementations\": [\n");
        List<Map.Entry<String, String>> entries = excludedServiceImplementations.entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .toList();
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, String> entry = entries.get(i);
            indent(json, 2).append("{\n");
            appendString(json, 3, CLASS_NAME, entry.getKey(), true);
            appendString(json, 3, "reason", entry.getValue(), false);
            indent(json, 2).append("}");
            if (i < entries.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        indent(json, 1).append("],\n");
    }

    private static void appendDiagnostics(StringBuilder json, Map<String, List<String>> diagnostics) {
        indent(json, 1).append("\"diagnostics\": [\n");
        List<Map.Entry<String, List<String>>> entries = diagnostics.entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .toList();
        int total = entries.stream().mapToInt(entry -> entry.getValue().size()).sum();
        int written = 0;
        for (Map.Entry<String, List<String>> entry : entries) {
            List<String> messages = entry.getValue()
                .stream()
                .sorted(Comparator.naturalOrder())
                .toList();
            for (String message : messages) {
                indent(json, 2).append("{\n");
                appendString(json, 3, "category", entry.getKey(), true);
                appendString(json, 3, "message", message, false);
                indent(json, 2).append("}");
                written++;
                if (written < total) {
                    json.append(",");
                }
                json.append("\n");
            }
        }
        indent(json, 1).append("]\n");
    }

    private static void appendHtmlOptimizers(StringBuilder html,
                                             List<SourceGeneratorSelection> sourceGenerators,
                                             DefaultSourceGenerationContext context) {
        html.append("  <h2>Optimizers</h2>\n");
        html.append(HTML_TABLE_START);
        html.append("    <tr><th>ID</th><th>Class</th><th>Description</th><th>Enabled</th><th>Disabled reason</th><th>Options</th><th>Dependencies</th><th>Runtimes</th></tr>\n");
        for (SourceGeneratorSelection selection : sourceGenerators) {
            AOTModule module = selection.getModule();
            html.append(HTML_ROW_START);
            appendHtmlCell(html, module == null ? null : module.id());
            appendHtmlCell(html, selection.getGenerator().getClass().getName());
            appendHtmlCell(html, module == null ? null : module.description());
            appendHtmlCell(html, String.valueOf(selection.isEnabled()));
            appendHtmlCell(html, selection.getDisabledReason());
            appendHtmlCell(html, module == null ? "" : optionsSummary(module.options(), context));
            appendHtmlCell(html, module == null ? "" : String.join(", ", module.dependencies()));
            appendHtmlCell(html, module == null ? "" : String.join(", ", Arrays.stream(module.enabledOn()).map(Runtime::displayName).toList()));
            html.append(HTML_ROW_END);
        }
        html.append(HTML_TABLE_END);
    }

    private static String optionsSummary(Option[] options, DefaultSourceGenerationContext context) {
        return Arrays.stream(options)
            .map(option -> option.key() + " (configured: " + context.getConfiguration().containsKey(option.key()) + ", redacted: " + context.getConfiguration().containsKey(option.key()) + ")")
            .sorted()
            .toList()
            .toString();
    }

    private static void appendHtmlGeneratedSources(StringBuilder html, List<JavaFile> generatedJavaFiles) {
        html.append("  <h2>Generated Sources</h2>\n");
        html.append(HTML_TABLE_START);
        html.append("    <tr><th>Class</th><th>Path</th></tr>\n");
        for (JavaFile javaFile : generatedJavaFiles) {
            String className = javaFile.packageName + "." + javaFile.typeSpec.name;
            html.append(HTML_ROW_START);
            appendHtmlCell(html, className);
            appendHtmlCell(html, className.replace('.', '/') + ".java");
            html.append(HTML_ROW_END);
        }
        html.append(HTML_TABLE_END);
    }

    private static void appendHtmlList(StringBuilder html, String title, Collection<String> values) {
        html.append("  <h2>").append(escapeHtml(title)).append("</h2>\n");
        html.append("  <ul>\n");
        for (String value : values) {
            html.append("    <li><code>").append(escapeHtml(value)).append("</code></li>\n");
        }
        html.append("  </ul>\n");
    }

    private static void appendHtmlExcludedServices(StringBuilder html, Map<String, String> excludedServiceImplementations) {
        html.append("  <h2>Excluded Service Implementations</h2>\n");
        html.append(HTML_TABLE_START);
        html.append("    <tr><th>Class</th><th>Reason</th></tr>\n");
        excludedServiceImplementations.entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                html.append(HTML_ROW_START);
                appendHtmlCell(html, entry.getKey());
                appendHtmlCell(html, entry.getValue());
                html.append(HTML_ROW_END);
            });
        html.append(HTML_TABLE_END);
    }

    private static void appendHtmlDiagnostics(StringBuilder html, Map<String, List<String>> diagnostics) {
        html.append("  <h2>Diagnostics</h2>\n");
        html.append(HTML_TABLE_START);
        html.append("    <tr><th>Category</th><th>Message</th></tr>\n");
        diagnostics.entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> entry.getValue()
                .stream()
                .sorted(Comparator.naturalOrder())
                .forEach(message -> {
                    html.append(HTML_ROW_START);
                    appendHtmlCell(html, entry.getKey());
                    appendHtmlCell(html, message);
                    html.append(HTML_ROW_END);
                }));
        html.append(HTML_TABLE_END);
    }

    private static void appendHtmlRow(StringBuilder html, String name, @Nullable String value) {
        html.append("    <tr><th>");
        html.append(escapeHtml(name));
        html.append("</th>");
        appendHtmlCell(html, value);
        html.append(HTML_ROW_END);
    }

    private static void appendHtmlCell(StringBuilder html, @Nullable String value) {
        html.append("<td>");
        html.append(escapeHtml(value == null ? "" : value));
        html.append("</td>");
    }

    private static String escapeHtml(String value) {
        var escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static void appendStringArray(StringBuilder json,
                                          int depth,
                                          String name,
                                          Collection<String> values,
                                          boolean comma) {
        indent(json, depth).append('"').append(name).append("\": [");
        int i = 0;
        for (String value : values) {
            if (i > 0) {
                json.append(", ");
            }
            appendQuoted(json, value);
            i++;
        }
        json.append("]");
        appendCommaAndNewLine(json, comma);
    }

    private static void appendString(StringBuilder json, int depth, String name, @Nullable String value, boolean comma) {
        indent(json, depth).append('"').append(name).append("\": ");
        if (value == null) {
            json.append("null");
        } else {
            appendQuoted(json, value);
        }
        appendCommaAndNewLine(json, comma);
    }

    private static void appendNumber(StringBuilder json, int depth, String name, int value, boolean comma) {
        indent(json, depth).append('"').append(name).append("\": ").append(value);
        appendCommaAndNewLine(json, comma);
    }

    private static void appendBoolean(StringBuilder json, int depth, String name, boolean value, boolean comma) {
        indent(json, depth).append('"').append(name).append("\": ").append(value);
        appendCommaAndNewLine(json, comma);
    }

    private static void appendQuoted(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }

    private static StringBuilder indent(StringBuilder json, int depth) {
        return json.append("  ".repeat(depth));
    }

    private static void appendCommaAndNewLine(StringBuilder json, boolean comma) {
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }
}
