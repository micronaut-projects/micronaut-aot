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
package io.micronaut.aot.internal;

/**
 * Utility methods to deal with exceptions being
 * thrown in streams.
 */
public abstract class StreamHelper {

    public static <T> T trying(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static void trying(ThrowingRunner runner) {
        try {
            runner.run();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * A helper interface to capture suppliers which
     * may throw an exception.
     *
     * @param <T> the type of the supplied value
     */
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * A helper interface for runnables which may
     * throw an exception.
     */
    public interface ThrowingRunner {
        void run() throws Exception;
    }

}
