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
package io.micronaut.aot;

/**
 * Configuration keys which may be found in the properties
 * used to configure the AOT analyzer, but shouldn't be
 * declared directly by the user.
 */
public interface ConfigKeys {
    String CLASSPATH = "classpath";
    String GENERATED_PACKAGE = "package";
    String OUTPUT_DIRECTORY = "output.directory";
    String RUNTIME = "runtime";
    String REPORT_ENABLED = "micronaut.aot.report.enabled";
    String REPORT_OUTPUT = "micronaut.aot.report.output";
    String REPORT_FORMAT = "micronaut.aot.report.format";
    String REPORT_FORMAT_JSON = "json";
    String REPORT_FORMAT_HTML = "html";
    String REPORT_JSON_FILE_NAME = "micronaut-aot-report.json";
    String REPORT_HTML_FILE_NAME = "micronaut-aot-report.html";
    String REPORT_FILE_NAME = "micronaut-aot-report.json";
}
