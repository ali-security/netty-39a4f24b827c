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
import org.jboss.netty.handler.codec.PrematureChannelClosureException;
import org.jboss.netty.handler.codec.embedder.CodecEmbedderException;
import org.jboss.netty.handler.codec.embedder.DecoderEmbedder;
import org.jboss.netty.handler.codec.embedder.EncoderEmbedder;
import org.jboss.netty.util.CharsetUtil;
import org.junit.Test;

public class HttpClientCodecTest {

    private static final String RESPONSE = "HTTP/1.0 200 OK\r\n" + "Date: Fri, 31 Dec 1999 23:59:59 GMT\r\n" + "Content-Type: text/html\r\n" + "Content-Length: 28\r\n" + "\r\n"
            + "<html><body></body></html>\r\n";
    private static final String INCOMPLETE_CHUNKED_RESPONSE = "HTTP/1.1 200 OK\r\n" + "Content-Type: text/plain\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n"
            +"5\r\n" + "first\r\n" + "6\r\n" + "second\r\n" + "0\r\n";
    private static final String CHUNKED_RESPONSE = INCOMPLETE_CHUNKED_RESPONSE + "\r\n";

    @Test
    public void testFailsNotOnRequestResponse() {
        HttpClientCodec codec = new HttpClientCodec(4096, 8192, 8192, true);
        DecoderEmbedder<ChannelBuffer> decoder = new DecoderEmbedder<ChannelBuffer>(codec);
        EncoderEmbedder<ChannelBuffer> encoder = new EncoderEmbedder<ChannelBuffer>(codec);

        encoder.offer(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://localhost/"));
        decoder.offer(ChannelBuffers.copiedBuffer(RESPONSE, CharsetUtil.ISO_8859_1));

        encoder.finish();
        decoder.finish();

    }

    @Test
    public void testFailsNotOnRequestResponseChunked() {
        HttpClientCodec codec = new HttpClientCodec(4096, 8192, 8192, true);
        DecoderEmbedder<ChannelBuffer> decoder = new DecoderEmbedder<ChannelBuffer>(codec);
        EncoderEmbedder<ChannelBuffer> encoder = new EncoderEmbedder<ChannelBuffer>(codec);

        encoder.offer(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://localhost/"));
        decoder.offer(ChannelBuffers.copiedBuffer(CHUNKED_RESPONSE, CharsetUtil.ISO_8859_1));

        encoder.finish();
        decoder.finish();

    }

    @Test
    public void testFailsOnMissingResponse() {
        HttpClientCodec codec = new HttpClientCodec(4096, 8192, 8192, true);
        EncoderEmbedder<ChannelBuffer> encoder = new EncoderEmbedder<ChannelBuffer>(codec);

        encoder.offer(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://localhost/"));

        try {
            encoder.finish();
            fail();
        } catch (CodecEmbedderException e) {
            assertTrue(e.getCause() instanceof PrematureChannelClosureException);
        }

    }

    @Test
    public void testFailsOnIncompleteChunkedResponse() {
        HttpClientCodec codec = new HttpClientCodec(4096, 8192, 8192, true);
        DecoderEmbedder<ChannelBuffer> decoder = new DecoderEmbedder<ChannelBuffer>(codec);

        EncoderEmbedder<ChannelBuffer> encoder = new EncoderEmbedder<ChannelBuffer>(codec);

        encoder.offer(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://localhost/"));
        decoder.offer(ChannelBuffers.copiedBuffer(INCOMPLETE_CHUNKED_RESPONSE, CharsetUtil.ISO_8859_1));

        try {
            encoder.finish();
            decoder.finish();
            fail();
        } catch (CodecEmbedderException e) {
            assertTrue(e.getCause() instanceof PrematureChannelClosureException);
        }

    }

    @Test
    public void testInformationalResponseKeepsPairsInSync() {
        HttpClientCodec codec = new HttpClientCodec();
        DecoderEmbedder<Object> decoder = new DecoderEmbedder<Object>(codec);
        EncoderEmbedder<ChannelBuffer> encoder = new EncoderEmbedder<ChannelBuffer>(codec);

        encoder.offer(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, "/"));
        decoder.offer(ChannelBuffers.copiedBuffer("HTTP/1.1 102 Processing\r\n" +
                "Status-URI: Status-URI:http://status.com; 404\r\n" +
                "\r\n" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 5\r\n" +
                "\r\n", CharsetUtil.ISO_8859_1));

        HttpResponse res = (HttpResponse) decoder.poll();
        assertEquals(102, res.getStatus().getCode());
        assertFalse(res.getContent().readable());

        res = (HttpResponse) decoder.poll();
        assertEquals(200, res.getStatus().getCode());
        assertEquals("5", res.getHeader(HttpHeaders.Names.CONTENT_LENGTH));
        assertFalse(res.getContent().readable());
        assertNull(decoder.poll());

        encoder.offer(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        decoder.offer(ChannelBuffers.copiedBuffer("HTTP/1.1 200 OK\r\n" +
                "Content-Length: 8\r\n" +
                "\r\n" +
                "12345678", CharsetUtil.ISO_8859_1));

        res = (HttpResponse) decoder.poll();
        assertEquals(200, res.getStatus().getCode());
        assertEquals("12345678", res.getContent().toString(CharsetUtil.ISO_8859_1));
        assertNull(decoder.poll());
    }

    @Test
    public void testInformationalFollowedByResponse() {
        HttpClientCodec codec = new HttpClientCodec();
        DecoderEmbedder<Object> decoder = new DecoderEmbedder<Object>(codec);
        EncoderEmbedder<ChannelBuffer> encoder = new EncoderEmbedder<ChannelBuffer>(codec);

        encoder.offer(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/1"));
        encoder.offer(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, "/2"));

        decoder.offer(ChannelBuffers.copiedBuffer(
                "HTTP/1.1 103 Early Hints\r\n\r\n" +
                "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello" +
                "HTTP/1.1 200 OK\r\n\r\n", CharsetUtil.US_ASCII));

        HttpResponse res = (HttpResponse) decoder.poll();
        assertEquals(103, res.getStatus().getCode());
        assertFalse(res.getContent().readable());

        res = (HttpResponse) decoder.poll();
        assertEquals(200, res.getStatus().getCode());
        assertEquals("5", res.getHeader(HttpHeaders.Names.CONTENT_LENGTH));
        assertEquals("hello", res.getContent().toString(CharsetUtil.US_ASCII));

        res = (HttpResponse) decoder.poll();
        assertEquals(200, res.getStatus().getCode());
        assertFalse(res.getContent().readable());
        assertNull(decoder.poll());
    }
}
