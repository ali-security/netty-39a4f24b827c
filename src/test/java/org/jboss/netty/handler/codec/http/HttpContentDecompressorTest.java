/*
 * Copyright 2019 The Netty Project
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
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.embedder.DecoderEmbedder;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

public class HttpContentDecompressorTest {

    @Test
    public void testZipBombGzip() throws IOException {
        testZipBomb("gzip");
    }

    @Test
    public void testZipBombDeflate() throws IOException {
        testZipBomb("deflate");
    }

    private static void testZipBomb(String encoding) throws IOException {
        int chunkSize = 1024 * 1024;
        int numberOfChunks = 64;

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        DeflaterOutputStream out = "gzip".equals(encoding) ?
                new GZIPOutputStream(compressed) : new DeflaterOutputStream(compressed);
        byte[] zeros = new byte[chunkSize];
        for (int i = 0; i < numberOfChunks; i++) {
            out.write(zeros);
        }
        out.close();

        ZipBombIncomingHandler incomingHandler = new ZipBombIncomingHandler();
        DecoderEmbedder<Object> decoder =
                new DecoderEmbedder<Object>(new HttpContentDecompressor(), incomingHandler);

        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.setHeader(HttpHeaders.Names.CONTENT_ENCODING, encoding);
        response.setHeader(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
        response.setChunked(true);
        decoder.offer(response);
        decoder.offer(new DefaultHttpChunk(ChannelBuffers.wrappedBuffer(compressed.toByteArray())));
        decoder.offer(HttpChunk.LAST_CHUNK);
        decoder.finish();

        assertTrue(incomingHandler.responseReceived);
        assertTrue(incomingHandler.lastChunkReceived);
        assertEquals((long) chunkSize * numberOfChunks, incomingHandler.total);
        assertTrue(incomingHandler.chunks > 1);
        assertTrue("largest chunk " + incomingHandler.maxChunk, incomingHandler.maxChunk < chunkSize);
    }

    private static final class ZipBombIncomingHandler extends SimpleChannelUpstreamHandler {
        boolean responseReceived;
        boolean lastChunkReceived;
        long total;
        int chunks;
        int maxChunk;

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
            Object msg = e.getMessage();
            if (msg instanceof HttpMessage) {
                responseReceived = true;
            } else if (msg instanceof HttpChunk) {
                HttpChunk chunk = (HttpChunk) msg;
                if (chunk.isLast()) {
                    lastChunkReceived = true;
                } else {
                    int size = chunk.getContent().readableBytes();
                    total += size;
                    chunks++;
                    maxChunk = Math.max(maxChunk, size);
                }
            }
        }
    }
}
