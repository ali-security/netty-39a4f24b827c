/*
 * Copyright 2021 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.jboss.netty.util.internal;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Utility that provides operations whose implementation depends on the
 * capabilities of the current runtime environment.
 *
 * @see <a href="https://github.com/netty/netty/security/advisories/GHSA-5mcr-gq6c-3hq2">CVE-2021-21290</a>
 */
public final class PlatformDependent {

    /** {@code java.nio.file.Files.createTempFile(String, String, FileAttribute...)} (Java 7+) */
    private static final Method FILES_CREATE_TEMP_FILE;
    /** {@code java.nio.file.Files.createTempFile(Path, String, String, FileAttribute...)} (Java 7+) */
    private static final Method FILES_CREATE_TEMP_FILE_IN_DIR;
    /** {@code java.io.File.toPath()} (Java 7+) */
    private static final Method FILE_TO_PATH;
    /** {@code java.nio.file.Path.toFile()} (Java 7+) */
    private static final Method PATH_TO_FILE;
    /** Empty {@code java.nio.file.attribute.FileAttribute[]} (Java 7+) */
    private static final Object EMPTY_FILE_ATTRIBUTES;
    /** {@code java.io.File.setReadable(boolean, boolean)} (Java 6+) */
    private static final Method FILE_SET_READABLE;

    static {
        Method createTempFile = null;
        Method createTempFileInDir = null;
        Method toPath = null;
        Method toFile = null;
        Object emptyAttributes = null;
        if (DetectionUtil.javaVersion() >= 7) {
            try {
                Class<?> filesClass = Class.forName("java.nio.file.Files");
                Class<?> pathClass = Class.forName("java.nio.file.Path");
                Class<?> attributeClass = Class.forName("java.nio.file.attribute.FileAttribute");
                Object attributes = Array.newInstance(attributeClass, 0);
                Class<?> attributesClass = attributes.getClass();

                Method m1 = filesClass.getMethod(
                        "createTempFile", String.class, String.class, attributesClass);
                Method m2 = filesClass.getMethod(
                        "createTempFile", pathClass, String.class, String.class, attributesClass);
                Method m3 = File.class.getMethod("toPath");
                Method m4 = pathClass.getMethod("toFile");

                createTempFile = m1;
                createTempFileInDir = m2;
                toPath = m3;
                toFile = m4;
                emptyAttributes = attributes;
            } catch (Exception e) {
                // NIO.2 is not usable - fall back to File.createTempFile(...) and adjust the
                // permissions afterwards.
            }
        }
        FILES_CREATE_TEMP_FILE = createTempFile;
        FILES_CREATE_TEMP_FILE_IN_DIR = createTempFileInDir;
        FILE_TO_PATH = toPath;
        PATH_TO_FILE = toFile;
        EMPTY_FILE_ATTRIBUTES = emptyAttributes;

        Method setReadable = null;
        try {
            setReadable = File.class.getMethod("setReadable", boolean.class, boolean.class);
        } catch (NoSuchMethodException e) {
            // Java 5 - no way to adjust the file permissions.
        }
        FILE_SET_READABLE = setReadable;
    }

    /**
     * Creates a new empty temporary file which is only readable by its owner.
     * <p>
     * Unlike {@link File#createTempFile(String, String, File)}, which creates the file with
     * the default (usually world-readable) permissions, this method uses
     * {@code java.nio.file.Files.createTempFile(...)} on Java 7+, which restricts access to
     * the owner, and otherwise restricts the read permission of the created file to its owner.
     *
     * @param prefix    the prefix string to be used in generating the file's name
     * @param suffix    the suffix string to be used in generating the file's name,
     *                  may be {@code null} in which case {@code ".tmp"} is used
     * @param directory the directory in which the file is to be created,
     *                  or {@code null} if the default temporary-file directory is to be used
     * @return the newly-created file
     * @throws IOException if the file could not be created or its permissions could not be set
     */
    public static File createTempFile(String prefix, String suffix, File directory) throws IOException {
        if (FILES_CREATE_TEMP_FILE != null) {
            Object path;
            if (directory == null) {
                path = invoke(FILES_CREATE_TEMP_FILE, null, prefix, suffix, EMPTY_FILE_ATTRIBUTES);
            } else {
                Object dirPath = invoke(FILE_TO_PATH, directory);
                path = invoke(FILES_CREATE_TEMP_FILE_IN_DIR, null, dirPath, prefix, suffix, EMPTY_FILE_ATTRIBUTES);
            }
            return (File) invoke(PATH_TO_FILE, path);
        }

        final File file;
        if (directory == null) {
            file = File.createTempFile(prefix, suffix);
        } else {
            file = File.createTempFile(prefix, suffix, directory);
        }

        // Try to adjust the perms, if this fails there is not much else we can do...
        if (FILE_SET_READABLE != null) {
            if (!setReadable(file, false, false)) {
                throw new IOException("Failed to set permissions on temporary file " + file);
            }
            if (!setReadable(file, true, true)) {
                throw new IOException("Failed to set permissions on temporary file " + file);
            }
        }
        return file;
    }

    private static boolean setReadable(File file, boolean readable, boolean ownerOnly) throws IOException {
        Object result = invoke(FILE_SET_READABLE, file, readable, ownerOnly);
        return Boolean.TRUE.equals(result);
    }

    private static Object invoke(Method method, Object target, Object... args) throws IOException {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof IllegalArgumentException) {
                // Thrown by Files.createTempFile(...) when the prefix or suffix can not be used to
                // generate a file name (e.g. contains a name-separator).
                throw newIOException(cause.getMessage(), cause);
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw newIOException("Failed to create temporary file", cause);
        } catch (IllegalAccessException e) {
            throw newIOException("Failed to create temporary file", e);
        }
    }

    private static IOException newIOException(String message, Throwable cause) {
        IOException e = new IOException(message);
        e.initCause(cause);
        return e;
    }

    private PlatformDependent() {
        // only static method supported
    }
}
