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

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

/**
 * Unit test for {@link PlatformDependent}.
 */
public class PlatformDependentTest {

    @Test
    public void testCreateTempFileWithoutDirectory() throws Exception {
        File file = PlatformDependent.createTempFile("netty-test-", ".tmp", null);
        try {
            assertTrue(file.isFile());
            assertTrue(file.getName().startsWith("netty-test-"));
            assertTrue(file.getName().endsWith(".tmp"));
            assertEquals(0, file.length());
            assertEquals(new File(System.getProperty("java.io.tmpdir")).getCanonicalFile(),
                    file.getParentFile().getCanonicalFile());
            assertOwnerOnly(file);
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test
    public void testCreateTempFileWithDirectory() throws Exception {
        File dir = newTempDirectory();
        try {
            File file = PlatformDependent.createTempFile("netty-test-", ".tmp", dir);
            try {
                assertTrue(file.isFile());
                assertTrue(file.getName().startsWith("netty-test-"));
                assertTrue(file.getName().endsWith(".tmp"));
                assertEquals(dir.getCanonicalFile(), file.getParentFile().getCanonicalFile());
                assertOwnerOnly(file);
            } finally {
                assertTrue(file.delete());
            }
        } finally {
            assertTrue(dir.delete());
        }
    }

    @Test
    public void testCreateTempFileWithNullSuffix() throws Exception {
        File file = PlatformDependent.createTempFile("netty-test-", null, null);
        try {
            assertTrue(file.isFile());
            assertTrue(file.getName().endsWith(".tmp"));
            assertOwnerOnly(file);
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test
    public void testCreateTempFileCreatesDistinctFiles() throws Exception {
        File file1 = PlatformDependent.createTempFile("netty-test-", ".tmp", null);
        try {
            File file2 = PlatformDependent.createTempFile("netty-test-", ".tmp", null);
            try {
                assertFalse(file1.equals(file2));
            } finally {
                assertTrue(file2.delete());
            }
        } finally {
            assertTrue(file1.delete());
        }
    }

    @Test
    public void testCreateTempFileWithNameSeparatorInSuffix() throws Exception {
        // Callers (e.g. the multipart decoder) only expect an IOException for a suffix that
        // can not be used to generate a temporary file name.
        try {
            File file = PlatformDependent.createTempFile("netty-test-", "_a/b", null);
            file.delete();
            fail("expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test(expected = IOException.class)
    public void testCreateTempFileInNonExistingDirectory() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "netty-non-existing-" + System.nanoTime());
        assertFalse(dir.exists());
        PlatformDependent.createTempFile("netty-test-", ".tmp", dir);
    }

    private static File newTempDirectory() throws IOException {
        File dir = File.createTempFile("netty-test-dir-", "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdir());
        return dir;
    }

    /**
     * Asserts that the given file is readable and writable by its owner only (CVE-2021-21290).
     */
    public static void assertOwnerOnly(File file) throws Exception {
        assertTrue(file.canRead());
        assertTrue(file.canWrite());

        Set<String> permissions = posixFilePermissions(file);
        // Only verifiable on Java 7+ with a POSIX file system.
        assumeNotNull(permissions);
        assertTrue(permissions.toString(), permissions.contains("OWNER_READ"));
        assertTrue(permissions.toString(), permissions.contains("OWNER_WRITE"));
        assertFalse(permissions.toString(), permissions.contains("GROUP_READ"));
        assertFalse(permissions.toString(), permissions.contains("GROUP_WRITE"));
        assertFalse(permissions.toString(), permissions.contains("OTHERS_READ"));
        assertFalse(permissions.toString(), permissions.contains("OTHERS_WRITE"));
    }

    /**
     * Returns the names of the POSIX permissions of the given file, or {@code null} if they can not
     * be determined (Java 5/6 or a non-POSIX file system).
     */
    public static Set<String> posixFilePermissions(File file) throws Exception {
        Class<?> filesClass;
        Class<?> pathClass;
        Class<?> linkOptionClass;
        try {
            filesClass = Class.forName("java.nio.file.Files");
            pathClass = Class.forName("java.nio.file.Path");
            linkOptionClass = Class.forName("java.nio.file.LinkOption");
        } catch (ClassNotFoundException e) {
            return null;
        }
        Object path = File.class.getMethod("toPath").invoke(file);
        Object linkOptions = Array.newInstance(linkOptionClass, 0);
        Method getPosixFilePermissions =
                filesClass.getMethod("getPosixFilePermissions", pathClass, linkOptions.getClass());
        Set<?> permissions;
        try {
            permissions = (Set<?>) getPosixFilePermissions.invoke(null, path, linkOptions);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof UnsupportedOperationException) {
                return null;
            }
            throw e;
        }
        Set<String> names = new HashSet<String>();
        for (Object permission : permissions) {
            names.add(String.valueOf(permission));
        }
        return names;
    }
}
