/*
 * Copyright 2026 The Netty Project
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

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.embedder.CodecEmbedderException;
import org.jboss.netty.handler.codec.embedder.DecoderEmbedder;
import org.jboss.netty.util.CharsetUtil;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpMethodTest {

    private static final String NUL = String.valueOf((char) 0x00);

    @Test
    public void valueOfReturnsCachedInstanceForKnownMethods() {
        assertSame(HttpMethod.GET, HttpMethod.valueOf("GET"));
        assertSame(HttpMethod.POST, HttpMethod.valueOf("POST"));
    }

    @Test
    public void constructorAcceptsCustomMethodName() {
        HttpMethod custom = new HttpMethod("CUSTOM");
        assertEquals("CUSTOM", custom.getName());
    }

    @Test
    public void constructorRejectsLeadingAndTrailingSpaces() {
        assertRejected("  GET  ");
    }

    @Test
    public void constructorRejectsLeadingAndTrailingTabs() {
        assertRejected("\tGET\t");
    }

    @Test
    public void constructorRejectsEmptyName() {
        assertRejected("");
    }

    @Test
    public void constructorRejectsLeadingNul() {
        assertRejected(NUL + "GET");
    }

    @Test
    public void constructorRejectsTrailingNul() {
        assertRejected("GET" + NUL);
    }

    @Test
    public void constructorRejectsLeadingAndTrailingNul() {
        assertRejected(NUL + "GET" + NUL);
    }

    @Test
    public void constructorRejectsEmbeddedNul() {
        assertRejected("GE" + NUL + "T");
    }

    @Test
    public void constructorRejectsCarriageReturn() {
        assertRejected("GET\r");
    }

    @Test
    public void constructorRejectsLineFeed() {
        assertRejected("GET\n");
    }

    @Test
    public void constructorRejectsVerticalTab() {
        assertRejected("GET" + (char) 0x0B);
    }

    @Test
    public void constructorRejectsFormFeed() {
        assertRejected("GET\f");
    }

    @Test
    public void constructorRejectsEmbeddedSpace() {
        assertRejected("GE T");
    }

    @Test
    public void constructorRejectsBlankString() {
        assertRejected("   ");
    }

    @Test
    public void valueOfRejectsTrailingNul() {
        assertValueOfRejected("GET" + NUL);
    }

    @Test
    public void valueOfRejectsLeadingAndTrailingNul() {
        assertValueOfRejected(NUL + "POST" + NUL);
    }

    @Test
    public void valueOfRejectsLeadingAndTrailingSpaces() {
        assertValueOfRejected(" GET ");
    }

    @Test
    public void valueOfRejectsBlankString() {
        assertValueOfRejected("   ");
    }

    @Test
    public void requestDecoderRejectsNulPaddedMethod() {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpRequestDecoder());
        try {
            embedder.offer(ChannelBuffers.copiedBuffer(
                    "GET" + NUL + " / HTTP/1.1\r\nHost: x\r\n\r\n", CharsetUtil.US_ASCII));
            fail("Expected CodecEmbedderException");
        } catch (CodecEmbedderException e) {
            assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof IllegalArgumentException);
        }
        assertNull(embedder.poll());
    }

    @Test
    public void requestDecoderAcceptsCleanMethod() {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpRequestDecoder());
        embedder.offer(ChannelBuffers.copiedBuffer("GET / HTTP/1.1\r\nHost: x\r\n\r\n", CharsetUtil.US_ASCII));
        HttpRequest req = (HttpRequest) embedder.poll();
        assertNotNull(req);
        assertSame(HttpMethod.GET, req.getMethod());
    }

    private static void assertRejected(String name) {
        try {
            new HttpMethod(name);
            fail("Expected IllegalArgumentException for: " + name);
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void assertValueOfRejected(String name) {
        try {
            HttpMethod.valueOf(name);
            fail("Expected IllegalArgumentException for: " + name);
        } catch (IllegalArgumentException expected) {
        }
    }
}
