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

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.embedder.CodecEmbedderException;
import org.jboss.netty.handler.codec.embedder.DecoderEmbedder;
import org.jboss.netty.util.CharsetUtil;

import org.junit.Test;

import static org.junit.Assert.*;

public class HttpMessageDecoderTest {

    @Test
    public void testHeaderWithNoValueAndMissingColon() {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpRequestDecoder());
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 0\r\n" +
                "Host:\r\n" +
                "netty.io\r\n\r\n";

        try {
            embedder.offer(ChannelBuffers.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
            fail("Expected CodecEmbedderException");
        } catch (CodecEmbedderException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void testMultipleContentLengthHeaders() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 0\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeaders2() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 1\r\n" +
                "Connection: close\r\n" +
                "Content-Length: 0\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersHttp10() {
        String requestStr = "GET /some/path HTTP/1.0\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 0\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersHttp09() {
        String requestStr = "GET /some/path HTTP/0.9\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 0\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersResponse() {
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 0\r\n\r\n" +
                "b";
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpResponseDecoder());
        try {
            embedder.offer(ChannelBuffers.copiedBuffer(responseStr, CharsetUtil.US_ASCII));
            fail("Expected CodecEmbedderException");
        } catch (CodecEmbedderException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void testContentLengthHeaderWithCommaValue() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 1,1\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderWithCommaValueAndChunked() {
        String requestStr = "POST / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Content-Length: 5, 5\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "0\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersWithFolding() {
        String requestStr = "POST / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Connection: close\r\n" +
                "Content-Length: 5\r\n" +
                "Content-Length:\r\n" +
                "\t6\r\n\r\n" +
                "123456";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testEmptyContentLength() {
        String requestStr = "POST / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Content-Length:\r\n\r\n";
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpRequestDecoder());
        try {
            embedder.offer(ChannelBuffers.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
            fail("Expected CodecEmbedderException");
        } catch (CodecEmbedderException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
            assertEquals("Content-Length value is not a number: ", e.getCause().getMessage());
        }
    }

    @Test
    public void testEmptyContentLengthAndChunked() {
        String requestStr = "POST / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Content-Length:\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "0\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthAndTransferEncodingChunked() {
        String requestStr = "POST / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Connection: close\r\n" +
                "Content-Length: 5\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "0\r\n\r\n";
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpRequestDecoder());
        embedder.offer(ChannelBuffers.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
        HttpRequest request = (HttpRequest) embedder.poll();
        assertTrue(request.isChunked());
        assertEquals(HttpHeaders.Values.CHUNKED, request.getHeader(HttpHeaders.Names.TRANSFER_ENCODING));
        assertFalse(request.containsHeader(HttpHeaders.Names.CONTENT_LENGTH));
        assertTrue(embedder.poll() instanceof HttpChunkTrailer);
        assertFalse(embedder.finish());
    }

    @Test
    public void testContentLengthAndTransferEncodingChunkedResponse() {
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 5\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "0\r\n\r\n";
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpResponseDecoder());
        embedder.offer(ChannelBuffers.copiedBuffer(responseStr, CharsetUtil.US_ASCII));
        HttpResponse response = (HttpResponse) embedder.poll();
        assertTrue(response.isChunked());
        assertEquals(HttpHeaders.Values.CHUNKED, response.getHeader(HttpHeaders.Names.TRANSFER_ENCODING));
        assertFalse(response.containsHeader(HttpHeaders.Names.CONTENT_LENGTH));
        assertTrue(embedder.poll() instanceof HttpChunkTrailer);
        assertFalse(embedder.finish());
    }

    @Test
    public void testContentLengthHeaderWithPositiveSign() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: +1\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderWithPositiveSignSmuggledRequest() {
        // A front-end that does not understand "+5" would forward the body as the start of a new request.
        String requestStr = "POST / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Content-Length: +5\r\n\r\n" +
                "helloGET /smuggled HTTP/1.1\r\n" +
                "Host: example.com\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderWithNegativeSign() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: -1\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderWithNegativeValue() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: -10\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderWithPositiveSignHttp10() {
        String requestStr = "GET /some/path HTTP/1.0\r\n" +
                "Content-Length: +1\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderNotANumber() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 1a\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderOverflow() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 99999999999999999999\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersSameValue() {
        // When allowDuplicateContentLengths is false (default), even identical values should be rejected
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 0\r\n" +
                "Content-Length: 0\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersSameValueWithComma() {
        // When allowDuplicateContentLengths is false (default), even identical comma-separated values should be rejected
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 0, 0\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersSameValueHttp10() {
        String requestStr = "PUT /some/path HTTP/1.0\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 1\r\n\r\n" +
                "a";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsSameValue() {
        String requestStr = "PUT /some/path HTTP/1.1\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 1\r\n\r\n" +
                "a";
        assertDuplicateContentLengthsNormalized(requestStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsSameValueWithComma() {
        String requestStr = "PUT /some/path HTTP/1.1\r\n" +
                "Content-Length: 1, 1\r\n\r\n" +
                "a";
        assertDuplicateContentLengthsNormalized(requestStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsSameValueHttp10() {
        String requestStr = "PUT /some/path HTTP/1.0\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 1\r\n\r\n" +
                "a";
        assertDuplicateContentLengthsNormalized(requestStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsDifferentValues() {
        testInvalidHeaders0(newAllowDuplicateContentLengthsDecoder(), multipleContentLengthRequest("HTTP/1.1"));
    }

    @Test
    public void testAllowDuplicateContentLengthsDifferentValuesWithComma() {
        String requestStr = "PUT /some/path HTTP/1.1\r\n" +
                "Content-Length: 1, 2\r\n\r\n" +
                "ab";
        testInvalidHeaders0(newAllowDuplicateContentLengthsDecoder(), requestStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsDifferentValuesHttp10() {
        testInvalidHeaders0(newAllowDuplicateContentLengthsDecoder(), multipleContentLengthRequest("HTTP/1.0"));
    }

    @Test
    public void testAllowDuplicateContentLengthsDifferentValuesHttp09() {
        testInvalidHeaders0(newAllowDuplicateContentLengthsDecoder(), multipleContentLengthRequest("HTTP/0.9"));
    }

    @Test
    public void testAllowDuplicateContentLengthsWithSign() {
        String requestStr = "PUT /some/path HTTP/1.1\r\n" +
                "Content-Length: +1\r\n" +
                "Content-Length: +1\r\n\r\n" +
                "a";
        testInvalidHeaders0(newAllowDuplicateContentLengthsDecoder(), requestStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsEmptyValue() {
        String requestStr = "PUT /some/path HTTP/1.1\r\n" +
                "Content-Length: ,\r\n\r\n" +
                "a";
        testInvalidHeaders0(newAllowDuplicateContentLengthsDecoder(), requestStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsAndChunked() {
        String requestStr = "POST / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Content-Length: 5, 5\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "0\r\n\r\n";
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(newAllowDuplicateContentLengthsDecoder());
        embedder.offer(ChannelBuffers.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
        HttpRequest request = (HttpRequest) embedder.poll();
        assertTrue(request.isChunked());
        assertFalse(request.containsHeader(HttpHeaders.Names.CONTENT_LENGTH));
        assertTrue(embedder.poll() instanceof HttpChunkTrailer);
        assertFalse(embedder.finish());
    }

    @Test
    public void testContentLengthHeaderWithPositiveSignResponse() {
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: +1\r\n\r\n" +
                "a";
        testInvalidHeaders0(new HttpResponseDecoder(), responseStr);
    }

    @Test
    public void testAllowDuplicateContentLengthsResponse() {
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 1\r\n\r\n" +
                "a";
        DecoderEmbedder<Object> embedder =
                new DecoderEmbedder<Object>(newAllowDuplicateContentLengthsResponseDecoder());
        embedder.offer(ChannelBuffers.copiedBuffer(responseStr, CharsetUtil.US_ASCII));
        Object msg = embedder.poll();
        assertTrue(msg instanceof HttpResponse);
        HttpResponse response = (HttpResponse) msg;
        assertEquals(1, response.getHeaders(HttpHeaders.Names.CONTENT_LENGTH).size());
        assertEquals("1", response.getHeader(HttpHeaders.Names.CONTENT_LENGTH));
        assertEquals("a", response.getContent().toString(CharsetUtil.US_ASCII));
        assertFalse(embedder.finish());
    }

    @Test
    public void testValidContentLength() {
        String requestStr = "PUT /some/path HTTP/1.1\r\n" +
                "Content-Length: 2\r\n\r\n" +
                "ab";
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpRequestDecoder());
        embedder.offer(ChannelBuffers.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
        Object msg = embedder.poll();
        assertTrue(msg instanceof HttpRequest);
        HttpRequest request = (HttpRequest) msg;
        assertEquals("2", request.getHeader(HttpHeaders.Names.CONTENT_LENGTH));
        assertEquals("ab", request.getContent().toString(CharsetUtil.US_ASCII));
        assertFalse(embedder.finish());
    }

    private static HttpMessageDecoder newAllowDuplicateContentLengthsDecoder() {
        final HttpRequestDecoder delegate = new HttpRequestDecoder();
        return new HttpMessageDecoder(4096, 8192, 8192, true) {
            @Override
            protected boolean isDecodingRequest() {
                return true;
            }

            @Override
            protected HttpMessage createMessage(String[] initialLine) throws Exception {
                return delegate.createMessage(initialLine);
            }
        };
    }

    private static HttpMessageDecoder newAllowDuplicateContentLengthsResponseDecoder() {
        final HttpResponseDecoder delegate = new HttpResponseDecoder();
        return new HttpMessageDecoder(4096, 8192, 8192, true) {
            @Override
            protected boolean isDecodingRequest() {
                return false;
            }

            @Override
            protected HttpMessage createMessage(String[] initialLine) throws Exception {
                return delegate.createMessage(initialLine);
            }
        };
    }

    private static void assertDuplicateContentLengthsNormalized(String requestStr) {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(newAllowDuplicateContentLengthsDecoder());
        embedder.offer(ChannelBuffers.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
        Object msg = embedder.poll();
        assertTrue(msg instanceof HttpRequest);
        HttpRequest request = (HttpRequest) msg;
        // The duplicated field-values must be replaced with a single valid Content-Length field
        assertEquals(1, request.getHeaders(HttpHeaders.Names.CONTENT_LENGTH).size());
        assertEquals("1", request.getHeader(HttpHeaders.Names.CONTENT_LENGTH));
        assertEquals("a", request.getContent().toString(CharsetUtil.US_ASCII));
        assertFalse(embedder.finish());
    }

    private static String multipleContentLengthRequest(String version) {
        return "PUT /some/path " + version + "\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 2\r\n\r\n" +
                "ab";
    }

    private static void testInvalidHeaders0(String requestStr) {
        testInvalidHeaders0(new HttpRequestDecoder(), requestStr);
    }

    private static void testInvalidHeaders0(HttpMessageDecoder decoder, String requestStr) {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(decoder);
        try {
            embedder.offer(ChannelBuffers.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
            fail("Expected CodecEmbedderException");
        } catch (CodecEmbedderException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }
}
