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

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.attribute.StackMapFrameInfo;
import java.lang.classfile.attribute.StackMapTableAttribute;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The stack map frames of a class as it was compiled, captured before a rewrite and attached again, by
 * label, once the rewrite is done.
 *
 * <p>Every rewritten class is written with {@link java.lang.classfile.ClassFile.StackMapsOption#DROP_STACK_MAPS}:
 * generating frames would need the whole class hierarchy and could produce frames that differ from the
 * compiler's, while copying the raw {@code StackMapTable} bytes would keep bytecode offsets that a rebuilt
 * constant pool moves (an {@code ldc} can become an {@code ldc_w}, and switch padding shifts). The frames are
 * therefore read from the class being transformed, whose labels the transformed code binds again, and written
 * back as a new {@link StackMapTableAttribute} at the end of each method's code. That is only correct because
 * every transform keeps its edits length- and stack-neutral: the frames describe the same instructions.</p>
 *
 * <p>The frames must come from the very {@link ClassModel} that is transformed, because a frame names its
 * targets with that model's labels. The class depends only on {@code java.lang.classfile}.</p>
 */
final class OriginalFrames {

    private final Map<String, List<StackMapFrameInfo>> frames;

    private OriginalFrames(Map<String, List<StackMapFrameInfo>> frames) {
        this.frames = frames;
    }

    /**
     * Captures the frames of every method of a class.
     *
     * @param model the class that is about to be transformed
     * @return its frames
     */
    static OriginalFrames of(ClassModel model) {
        Map<String, List<StackMapFrameInfo>> frames = new HashMap<>();
        for (MethodModel method : model.methods()) {
            Optional<CodeModel> code = method.code();
            if (code.isEmpty()) {
                continue;
            }
            Optional<StackMapTableAttribute> table = code.get().findAttribute(Attributes.stackMapTable());
            table.ifPresent(attribute -> frames.put(key(method), attribute.entries()));
        }
        return new OriginalFrames(frames);
    }

    /**
     * The transform that runs after every step: it passes each method's code through and attaches the frames
     * that method had, if it had any.
     *
     * @return the transform
     */
    ClassTransform reattaching() {
        return (builder, element) -> {
            if (element instanceof MethodModel method && method.code().isPresent()) {
                List<StackMapFrameInfo> entries = frames.get(key(method));
                builder.transformMethod(method, MethodTransform.transformingCode(new Reattach(entries)));
            } else {
                builder.with(element);
            }
        };
    }

    private static String key(MethodModel method) {
        return method.methodName().stringValue() + method.methodType().stringValue();
    }

    /**
     * Passes a method's code through and attaches its original frames at the end.
     */
    private static final class Reattach implements CodeTransform {

        private final List<StackMapFrameInfo> entries;

        private Reattach(List<StackMapFrameInfo> entries) {
            this.entries = entries;
        }

        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            builder.with(element);
        }

        @Override
        public void atEnd(CodeBuilder builder) {
            if (entries != null) {
                builder.with(StackMapTableAttribute.of(entries));
            }
        }
    }
}
