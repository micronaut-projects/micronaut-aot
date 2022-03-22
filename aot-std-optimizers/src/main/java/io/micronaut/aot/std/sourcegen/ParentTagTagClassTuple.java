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

/**
 *
 * Represents default class name rules.
 *
 * @author Ceki G&uuml;c&uuml;
 *
 */
public class ParentTagTagClassTuple {

    String parentTag;
    String tag;
    Class<?> aClass;

    public ParentTagTagClassTuple(String parentTag, String tag, Class<?> aClass) {
        super();
        this.parentTag = parentTag;
        this.tag = tag;
        this.aClass = aClass;
    }

    @Override
    public String toString() {
        return "ParentTag_Tag_Class_Tuple [parentTag=" + parentTag + ", tag=" + tag + ", aClass=" + aClass + "]";
    }
}
