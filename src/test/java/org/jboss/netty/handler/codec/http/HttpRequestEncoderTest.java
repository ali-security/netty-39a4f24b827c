/*
 * Copyright 2012 The Netty Project
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
package org.jboss.netty.handler.codec.http;

import static org.junit.Assert.*;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.junit.Test;

import java.nio.charset.Charset;

/**
 */
public class HttpRequestEncoderTest {

    private static final String SMUGGLING_URI =
            "/s1 HTTP/1.1\r\n\r\nPOST /s2 HTTP/1.1\r\ncontent-length: 11\r\n\r\nHello WorldGET /s1";
    
    @Test
    public void testUriWithoutPath() throws Exception {
        HttpRequestEncoder encoder = new HttpRequestEncoder();
        ChannelBuffer buffer = ChannelBuffers.buffer(64);
        encoder.encodeInitialLine(buffer, new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://localhost"));
        String req = buffer.toString(Charset.forName("US-ASCII"));
        assertEquals("GET http://localhost/ HTTP/1.1\r\n", req);
    }


    @Test
    public void testUriWithPath() throws Exception {
        HttpRequestEncoder encoder = new HttpRequestEncoder();
        ChannelBuffer buffer = ChannelBuffers.buffer(64);
        encoder.encodeInitialLine(buffer, new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://localhost/"));
        String req = buffer.toString(Charset.forName("US-ASCII"));
        assertEquals("GET http://localhost/ HTTP/1.1\r\n", req);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorMustNotAllowRequestSmuggling() {
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, SMUGGLING_URI);
    }

    @Test
    public void testSetUriMustNotAllowRequestSmuggling() throws Exception {
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/safe");
        IllegalArgumentException cause = null;
        try {
            request.setUri(SMUGGLING_URI);
        } catch (IllegalArgumentException e) {
            cause = e;
        }
        assertNotNull(cause);
        HttpRequestEncoder encoder = new HttpRequestEncoder();
        ChannelBuffer buffer = ChannelBuffers.buffer(64);
        encoder.encodeInitialLine(buffer, request);
        assertEquals("GET /safe HTTP/1.1\r\n", buffer.toString(Charset.forName("US-ASCII")));
    }
}
