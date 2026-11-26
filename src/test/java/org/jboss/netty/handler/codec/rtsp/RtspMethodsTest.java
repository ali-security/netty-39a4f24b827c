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
package org.jboss.netty.handler.codec.rtsp;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.embedder.CodecEmbedderException;
import org.jboss.netty.handler.codec.embedder.DecoderEmbedder;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.util.CharsetUtil;
import org.junit.Test;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RtspMethodsTest {

    @Test
    public void valueOfReturnsCachedInstanceForKnownMethods() {
        assertSame(RtspMethods.PLAY, RtspMethods.valueOf("PLAY"));
        assertSame(RtspMethods.SETUP, RtspMethods.valueOf("setup"));
    }

    @Test
    public void valueOfRejectsNulPaddedCachedMethodName() {
        assertValueOfRejected("\u0000PLAY");
    }

    @Test
    public void valueOfRejectsNulPaddedCachedMethodNameTrailing() {
        assertValueOfRejected("SETUP\u0000");
    }

    @Test
    public void valueOfRejectsCarriageReturnPaddedCachedMethodName() {
        assertValueOfRejected("\rTEARDOWN");
    }

    @Test
    public void valueOfDoesNotReturnCachedConstantForControlBytePaddedName() {
        try {
            HttpMethod result = RtspMethods.valueOf("\u0000PLAY");
            assertNotSame(RtspMethods.PLAY, result);
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void requestDecoderRejectsTrailingNulInMethodName() {
        DecoderEmbedder<HttpRequest> embedder = new DecoderEmbedder<HttpRequest>(new RtspRequestDecoder());
        try {
            embedder.offer(ChannelBuffers.copiedBuffer(
                    "PLAY\u0000 rtsp://172.20.184.218:554/stream RTSP/1.0\r\n" +
                    "CSeq: 1\r\n\r\n", CharsetUtil.ISO_8859_1));
            fail("Expected CodecEmbedderException");
        } catch (CodecEmbedderException e) {
            assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof IllegalArgumentException);
        }
        assertNull(embedder.poll());
    }

    private static void assertValueOfRejected(String name) {
        try {
            RtspMethods.valueOf(name);
            fail("Expected IllegalArgumentException for: " + name);
        } catch (IllegalArgumentException expected) {
        }
    }
}
