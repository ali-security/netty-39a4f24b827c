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
package org.jboss.netty.handler.codec.http.multipart;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.util.CharsetUtil;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;

import static org.jboss.netty.util.internal.PlatformDependentTest.*;
import static org.junit.Assert.*;

/**
 * Temporary files holding uploaded data must not be readable by other local users
 * (CVE-2021-21290).
 */
public class DiskFileUploadTest {

    @Test
    public void testDiskFileUploadTempFileIsOwnerOnly() throws Exception {
        DiskFileUpload upload = new DiskFileUpload(
                "file", "secret.txt", "text/plain", null, CharsetUtil.UTF_8, 0);
        try {
            upload.setContent(ChannelBuffers.copiedBuffer("secret", CharsetUtil.UTF_8));
            File file = upload.getFile();
            assertTrue(file.isFile());
            assertTrue(file.getName().startsWith(DiskFileUpload.prefix));
            assertTrue(file.getName().endsWith("_secret.txt"));
            assertEquals("secret", upload.getString(CharsetUtil.UTF_8));
            assertOwnerOnly(file);
        } finally {
            upload.delete();
        }
    }

    @Test
    public void testDiskFileUploadTempFileInBaseDirectoryIsOwnerOnly() throws Exception {
        File dir = File.createTempFile("netty-upload-dir-", "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdir());
        String oldBaseDirectory = DiskFileUpload.baseDirectory;
        DiskFileUpload.baseDirectory = dir.getAbsolutePath();
        try {
            DiskFileUpload upload = new DiskFileUpload(
                    "file", "secret.txt", "text/plain", null, CharsetUtil.UTF_8, 0);
            try {
                upload.addContent(ChannelBuffers.copiedBuffer("secret", CharsetUtil.UTF_8), true);
                File file = upload.getFile();
                assertTrue(file.isFile());
                assertEquals(dir.getCanonicalFile(), file.getParentFile().getCanonicalFile());
                assertOwnerOnly(file);
            } finally {
                upload.delete();
            }
        } finally {
            DiskFileUpload.baseDirectory = oldBaseDirectory;
            assertTrue(dir.delete());
        }
    }

    @Test
    public void testDiskFileUploadFromStreamTempFileIsOwnerOnly() throws Exception {
        DiskFileUpload upload = new DiskFileUpload(
                "file", "secret.txt", "text/plain", null, CharsetUtil.UTF_8, 0);
        File file = null;
        try {
            upload.setContent(new ByteArrayInputStream("secret".getBytes("UTF-8")));
            file = upload.getFile();
            assertTrue(file.isFile());
            assertOwnerOnly(file);
        } finally {
            if (file != null) {
                assertTrue(file.delete());
            }
        }
    }

    @Test
    public void testDiskAttributeTempFileIsOwnerOnly() throws Exception {
        DiskAttribute attribute = new DiskAttribute("password", "secret");
        try {
            File file = attribute.getFile();
            assertTrue(file.isFile());
            assertEquals("secret", attribute.getValue());
            assertOwnerOnly(file);
        } finally {
            attribute.delete();
        }
    }

    @Test
    public void testMultipartDecoderDiskTempFilesAreOwnerOnly() throws Exception {
        final String boundary = "dLV9Wyq26L_-JQxk6ferf-RT153LhOO";
        final String body =
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"password\"\r\n" +
                "\r\n" +
                "secret-attribute\r\n" +
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"secret.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "secret-file-content\r\n" +
                "--" + boundary + "--\r\n";

        final DefaultHttpRequest req =
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "http://localhost");
        req.setHeader(HttpHeaders.Names.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary);
        req.setContent(ChannelBuffers.copiedBuffer(body, CharsetUtil.UTF_8));

        // Force to use disk-based data.
        final DefaultHttpDataFactory diskFactory = new DefaultHttpDataFactory(true);
        final HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(diskFactory, req);
        try {
            InterfaceHttpData attributeData = decoder.getBodyHttpData("password");
            assertTrue(attributeData instanceof DiskAttribute);
            DiskAttribute attribute = (DiskAttribute) attributeData;
            assertEquals("secret-attribute", attribute.getValue());
            assertOwnerOnly(attribute.getFile());

            InterfaceHttpData uploadData = decoder.getBodyHttpData("file");
            assertTrue(uploadData instanceof DiskFileUpload);
            DiskFileUpload upload = (DiskFileUpload) uploadData;
            assertEquals("secret-file-content", upload.getString(CharsetUtil.UTF_8));
            assertOwnerOnly(upload.getFile());
        } finally {
            decoder.cleanFiles();
        }
    }
}
