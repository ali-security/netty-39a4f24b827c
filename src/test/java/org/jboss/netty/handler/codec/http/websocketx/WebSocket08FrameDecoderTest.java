/*
 * Copyright 2013 The Netty Project
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
package org.jboss.netty.handler.codec.http.websocketx;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.buffer.DirectChannelBufferFactory;
import org.jboss.netty.handler.codec.embedder.CodecEmbedderException;
import org.jboss.netty.handler.codec.embedder.DecoderEmbedder;
import org.jboss.netty.handler.codec.frame.CorruptedFrameException;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class WebSocket08FrameDecoderTest {

    private static final int OPCODE_CONT = 0x0;
    private static final int OPCODE_TEXT = 0x1;

    private static final byte[] MASKING_KEY = { 0x37, (byte) 0xfa, 0x21, 0x3d };

    @Test
    public void testFragmentedTextMessageIsAggregated() throws Exception {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new WebSocket08FrameDecoder(true, false));

        String text = "Hello Wörld!";
        byte[] utf8 = text.getBytes("UTF-8");
        // Split the message so that the two byte UTF-8 sequence of the umlaut spans two fragments.
        int firstSplit = 8;
        int secondSplit = 11;
        byte[] first = copyOfRange(utf8, 0, firstSplit);
        byte[] second = copyOfRange(utf8, firstSplit, secondSplit);
        byte[] third = copyOfRange(utf8, secondSplit, utf8.length);

        embedder.offer(maskedFrame(false, OPCODE_TEXT, first));
        embedder.offer(maskedFrame(false, OPCODE_CONT, second));
        embedder.offer(maskedFrame(true, OPCODE_CONT, third));

        TextWebSocketFrame textFrame = (TextWebSocketFrame) embedder.poll();
        assertFalse(textFrame.isFinalFragment());
        assertArrayEquals(first, toArray(textFrame.getBinaryData()));

        ContinuationWebSocketFrame middleFrame = (ContinuationWebSocketFrame) embedder.poll();
        assertFalse(middleFrame.isFinalFragment());
        assertArrayEquals(second, toArray(middleFrame.getBinaryData()));
        assertNull(middleFrame.getAggregatedText());

        ContinuationWebSocketFrame lastFrame = (ContinuationWebSocketFrame) embedder.poll();
        assertTrue(lastFrame.isFinalFragment());
        assertArrayEquals(third, toArray(lastFrame.getBinaryData()));
        assertEquals(text, lastFrame.getAggregatedText());

        assertNull(embedder.poll());
    }

    @Test
    public void testFragmentedTextMessageExceedingAggregationLimitIsRejected() throws Exception {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new WebSocket08FrameDecoder(true, false));

        byte[] fragment = new byte[65535];
        Arrays.fill(fragment, (byte) 'a');
        int acceptedFragments = UTF8Output.MAX_AGGREGATED_TEXT_BYTES / fragment.length;

        // An attacker opens a fragmented text message and never sends the final fragment. All fragments up to
        // the aggregation limit are still accepted.
        embedder.offer(maskedFrame(false, OPCODE_TEXT, fragment));
        for (int i = 1; i < acceptedFragments; i ++) {
            embedder.offer(maskedFrame(false, OPCODE_CONT, fragment));
        }
        assertEquals(acceptedFragments, embedder.pollAll().length);

        // The next fragment would grow the aggregated text beyond the limit and must be rejected.
        try {
            embedder.offer(maskedFrame(false, OPCODE_CONT, fragment));
            fail(CodecEmbedderException.class.getSimpleName() + " must be raised.");
        } catch (CodecEmbedderException e) {
            assertTrue(e.getCause() instanceof CorruptedFrameException);
        }

        // The decoder must not accept any further fragments of the message.
        embedder.offer(maskedFrame(false, OPCODE_CONT, fragment));
        embedder.offer(maskedFrame(true, OPCODE_CONT, fragment));
        for (Object msg: embedder.pollAll()) {
            assertFalse(msg instanceof WebSocketFrame);
        }
    }

    @Test
    public void testAggregationLimitIsAppliedPerMessage() throws Exception {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new WebSocket08FrameDecoder(true, false));

        byte[] fragment = new byte[65535];
        Arrays.fill(fragment, (byte) 'b');
        int fragmentsPerMessage = UTF8Output.MAX_AGGREGATED_TEXT_BYTES / fragment.length;

        // Two consecutive messages that each stay within the limit must both be decoded.
        for (int message = 0; message < 2; message ++) {
            embedder.offer(maskedFrame(false, OPCODE_TEXT, fragment));
            for (int i = 1; i < fragmentsPerMessage - 1; i ++) {
                embedder.offer(maskedFrame(false, OPCODE_CONT, fragment));
            }
            embedder.offer(maskedFrame(true, OPCODE_CONT, fragment));

            Object[] frames = embedder.pollAll();
            assertEquals(fragmentsPerMessage, frames.length);
            ContinuationWebSocketFrame lastFrame = (ContinuationWebSocketFrame) frames[frames.length - 1];
            assertTrue(lastFrame.isFinalFragment());
            assertEquals(fragmentsPerMessage * fragment.length, lastFrame.getAggregatedText().length());
        }
    }

    @Test
    public void testMaskedTextFrameInDirectBuffer() throws Exception {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new WebSocket08FrameDecoder(true, false));

        byte[] payload = "Hello Direct".getBytes("UTF-8");
        ChannelBuffer frame = maskedFrame(true, OPCODE_TEXT, payload);
        ChannelBuffer direct = ChannelBuffers.directBuffer(frame.readableBytes());
        direct.writeBytes(frame);
        assertFalse(direct.hasArray());

        embedder.offer(direct);

        TextWebSocketFrame textFrame = (TextWebSocketFrame) embedder.poll();
        assertTrue(textFrame.isFinalFragment());
        assertEquals("Hello Direct", textFrame.getText());
    }

    @Test
    public void testMaskedTextFrameAccumulatedInDirectBuffer() throws Exception {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(
                DirectChannelBufferFactory.getInstance(), new WebSocket08FrameDecoder(true, false));

        String text = "Hello accumulated direct payload";
        ChannelBuffer frame = maskedFrame(true, OPCODE_TEXT, text.getBytes("UTF-8"));
        // Deliver the frame in two chunks so the decoder accumulates the payload in a buffer obtained from the
        // channel's (direct) buffer factory.
        int split = frame.readableBytes() - 10;
        embedder.offer(frame.readSlice(split));
        assertNull(embedder.peek());
        embedder.offer(frame.readSlice(frame.readableBytes()));

        TextWebSocketFrame textFrame = (TextWebSocketFrame) embedder.poll();
        assertTrue(textFrame.isFinalFragment());
        assertEquals(text, textFrame.getText());
    }

    private static ChannelBuffer maskedFrame(boolean finalFragment, int opcode, byte[] payload) {
        ChannelBuffer frame = ChannelBuffers.dynamicBuffer(payload.length + 14);
        frame.writeByte((finalFragment ? 0x80 : 0) | opcode);
        if (payload.length < 126) {
            frame.writeByte(0x80 | payload.length);
        } else if (payload.length <= 65535) {
            frame.writeByte(0x80 | 126);
            frame.writeShort(payload.length);
        } else {
            frame.writeByte(0x80 | 127);
            frame.writeLong(payload.length);
        }
        frame.writeBytes(MASKING_KEY);
        for (int i = 0; i < payload.length; i ++) {
            frame.writeByte(payload[i] ^ MASKING_KEY[i % 4]);
        }
        return frame;
    }

    private static byte[] toArray(ChannelBuffer buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static byte[] copyOfRange(byte[] src, int from, int to) {
        byte[] dst = new byte[to - from];
        System.arraycopy(src, from, dst, 0, dst.length);
        return dst;
    }
}
