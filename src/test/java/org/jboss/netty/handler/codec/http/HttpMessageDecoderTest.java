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

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.embedder.CodecEmbedderException;
import org.jboss.netty.handler.codec.embedder.DecoderEmbedder;
import org.jboss.netty.util.CharsetUtil;

import org.junit.Test;

import java.util.Arrays;

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

    // ---------------------------------------------------------------------
    // GHSA-wx5j-54mm-rqqq: header names must be bracketed by OWS only.
    // The decoder used to silently strip every Character.isWhitespace char,
    // which let an attacker smuggle control characters around a header name.
    // The strict OWS check now rejects 0x0c (FF) and 0x1c-0x1f (FS/GS/RS/US)
    // both before the name and between the name and the colon.
    // ---------------------------------------------------------------------

    @Test
    public void testRequestHeaderNameStartsWithControlChar1c() {
        testRequestHeaderNameStartsWithControlChar(0x1c);
    }

    @Test
    public void testRequestHeaderNameStartsWithControlChar1d() {
        testRequestHeaderNameStartsWithControlChar(0x1d);
    }

    @Test
    public void testRequestHeaderNameStartsWithControlChar1e() {
        testRequestHeaderNameStartsWithControlChar(0x1e);
    }

    @Test
    public void testRequestHeaderNameStartsWithControlChar1f() {
        testRequestHeaderNameStartsWithControlChar(0x1f);
    }

    @Test
    public void testRequestHeaderNameStartsWithControlChar0c() {
        testRequestHeaderNameStartsWithControlChar(0x0c);
    }

    private static void testRequestHeaderNameStartsWithControlChar(int controlChar) {
        ChannelBuffer requestBuffer = ChannelBuffers.dynamicBuffer();
        requestBuffer.writeBytes("GET /some/path HTTP/1.1\r\nHost: netty.io\r\n".getBytes(CharsetUtil.US_ASCII));
        requestBuffer.writeByte(controlChar);
        requestBuffer.writeBytes("Transfer-Encoding: chunked\r\n\r\n".getBytes(CharsetUtil.US_ASCII));
        testInvalidRequestHeaders0(requestBuffer);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar1c() {
        testRequestHeaderNameEndsWithControlChar(0x1c);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar1d() {
        testRequestHeaderNameEndsWithControlChar(0x1d);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar1e() {
        testRequestHeaderNameEndsWithControlChar(0x1e);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar1f() {
        testRequestHeaderNameEndsWithControlChar(0x1f);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar0c() {
        testRequestHeaderNameEndsWithControlChar(0x0c);
    }

    private static void testRequestHeaderNameEndsWithControlChar(int controlChar) {
        ChannelBuffer requestBuffer = ChannelBuffers.dynamicBuffer();
        requestBuffer.writeBytes("GET /some/path HTTP/1.1\r\nHost: netty.io\r\n".getBytes(CharsetUtil.US_ASCII));
        requestBuffer.writeBytes("Transfer-Encoding".getBytes(CharsetUtil.US_ASCII));
        requestBuffer.writeByte(controlChar);
        requestBuffer.writeBytes(": chunked\r\n\r\n".getBytes(CharsetUtil.US_ASCII));
        testInvalidRequestHeaders0(requestBuffer);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar1c() {
        testResponseHeaderNameStartsWithControlChar(0x1c);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar1d() {
        testResponseHeaderNameStartsWithControlChar(0x1d);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar1e() {
        testResponseHeaderNameStartsWithControlChar(0x1e);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar1f() {
        testResponseHeaderNameStartsWithControlChar(0x1f);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar0c() {
        testResponseHeaderNameStartsWithControlChar(0x0c);
    }

    private static void testResponseHeaderNameStartsWithControlChar(int controlChar) {
        ChannelBuffer responseBuffer = ChannelBuffers.dynamicBuffer();
        responseBuffer.writeBytes("HTTP/1.1 200 OK\r\nHost: netty.io\r\n".getBytes(CharsetUtil.US_ASCII));
        responseBuffer.writeByte(controlChar);
        responseBuffer.writeBytes("Transfer-Encoding: chunked\r\n\r\n".getBytes(CharsetUtil.US_ASCII));
        testInvalidResponseHeaders0(responseBuffer);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar1c() {
        testResponseHeaderNameEndsWithControlChar(0x1c);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar1d() {
        testResponseHeaderNameEndsWithControlChar(0x1d);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar1e() {
        testResponseHeaderNameEndsWithControlChar(0x1e);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar1f() {
        testResponseHeaderNameEndsWithControlChar(0x1f);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar0c() {
        testResponseHeaderNameEndsWithControlChar(0x0c);
    }

    private static void testResponseHeaderNameEndsWithControlChar(int controlChar) {
        ChannelBuffer responseBuffer = ChannelBuffers.dynamicBuffer();
        responseBuffer.writeBytes("HTTP/1.1 200 OK\r\nHost: netty.io\r\n".getBytes(CharsetUtil.US_ASCII));
        responseBuffer.writeBytes("Transfer-Encoding".getBytes(CharsetUtil.US_ASCII));
        responseBuffer.writeByte(controlChar);
        responseBuffer.writeBytes(": chunked\r\n\r\n".getBytes(CharsetUtil.US_ASCII));
        testInvalidResponseHeaders0(responseBuffer);
    }

    // ---------------------------------------------------------------------
    // CVE-2019-16869: no whitespace is allowed between a header field-name and
    // the colon (RFC 7230 section 3.2.4). For requests this must be rejected
    // (400 Bad Request); for responses the whitespace is silently skipped so
    // that interop with non-compliant servers is preserved.
    // Backport of netty/netty@39cafcb (issue #9571 / PR #9585).
    // ---------------------------------------------------------------------

    @Test
    public void testWhitespaceInRequestHeaderName() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Transfer-Encoding : chunked\r\n" +
                "Host: netty.io\r\n\r\n";
        testInvalidRequestHeaders0(ChannelBuffers.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
    }

    @Test
    public void testTabInRequestHeaderName() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Transfer-Encoding\t: chunked\r\n" +
                "Host: netty.io\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testWhitespaceInRequestTrailerHeaderName() {
        String requestStr = "POST /some/path HTTP/1.1\r\n" +
                "Host: netty.io\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "0\r\n" +
                "Foo : bar\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testWhitespaceInResponseHeaderName() {
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding : chunked\r\n" +
                "Host: netty.io\r\n\r\n";
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpResponseDecoder());
        embedder.offer(ChannelBuffers.copiedBuffer(responseStr, CharsetUtil.US_ASCII));
        HttpResponse response = (HttpResponse) embedder.poll();
        assertNotNull(response);
        assertEquals(HttpHeaders.Values.CHUNKED,
                response.getHeader(HttpHeaders.Names.TRANSFER_ENCODING));
        assertEquals("netty.io", response.getHeader(HttpHeaders.Names.HOST));
    }

    // ---------------------------------------------------------------------
    // CVE-2026-33870 (GHSA-pwqr-wmgm-9rr8): chunk extensions must follow the
    // grammar of RFC 9112 section 7.1, so that line breaks (or other control
    // characters) hidden in a chunk extension, e.g. inside a quoted-string,
    // cannot be used to smuggle a request past a front-end proxy.
    // See https://w4ke.info/2025/10/29/funky-chunks-2.html
    // Backport of netty/netty@60e53c9 and netty/netty@0841072.
    // ---------------------------------------------------------------------

    private static final String CHUNKED_REQUEST_HEAD = "GET /one HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n";

    private static final String CHUNKED_RESPONSE_HEAD = "HTTP/1.1 200 OK\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n";

    @Test
    public void mustParseChunkExtensionsWithQuotedStringsRequest() {
        assertChunkLineAccepted(new HttpRequestDecoder(), CHUNKED_REQUEST_HEAD +
                "1;a=\" ;\t\"\r\n" + // chunk extension quote end
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustParseChunkExtensionsWithQuotedStringsResponse() {
        assertChunkLineAccepted(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                "1;a=\" ;\t\"\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustRejectChunkExtensionsWithLineBreaksInQuotedStringsRequest() {
        assertChunkLineRejected(new HttpRequestDecoder(), CHUNKED_REQUEST_HEAD +
                "1;a=\"\r\n" + // chunk extension quote start
                "X\r\n" +
                "0\r\n\r\n" +
                "GET /two HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "\"\r\n" + // chunk extension quote end
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustRejectChunkExtensionsWithLineBreaksInQuotedStringsResponse() {
        assertChunkLineRejected(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                "1;a=\"\r\n" + // chunk extension quote start
                "X\r\n" +
                "0\r\n\r\n" +
                "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "\"\r\n" + // chunk extension quote end
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustParseChunkExtensionsWithQuotedStringsAndEscapesRequest() {
        assertChunkLineAccepted(new HttpRequestDecoder(), CHUNKED_REQUEST_HEAD +
                "1;a=\" \\\";\t\"\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustParseChunkExtensionsWithQuotedStringsAndEscapesResponse() {
        assertChunkLineAccepted(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                "1;a=\" \\\";\t\"\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustParseMultipleChunkExtensionsWithTokenValuesRequest() {
        // ';' must end a token value and start a new extension, not be treated as a token character.
        assertChunkLineAccepted(new HttpRequestDecoder(), CHUNKED_REQUEST_HEAD +
                "1;name1=val1;name2=val2\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustParseMultipleChunkExtensionsWithTokenValuesResponse() {
        assertChunkLineAccepted(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                "1;name1=val1;name2=val2\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustRejectChunkExtensionsWithEscapedLineBreakInQuotedStringsRequest() {
        assertChunkLineRejected(new HttpRequestDecoder(), CHUNKED_REQUEST_HEAD +
                "1;a=\" \\\n;\t\"\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustRejectChunkExtensionsWithEscapedLineBreakInQuotedStringsResponse() {
        assertChunkLineRejected(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                "1;a=\" \\\n;\t\"\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustRejectChunkExtensionsWithEscapedCarriageReturnInQuotedStringsRequest() {
        assertChunkLineRejected(new HttpRequestDecoder(), CHUNKED_REQUEST_HEAD +
                "1;a=\" \\\r;\t\"\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustRejectChunkExtensionsWithEscapedCarriageReturnInQuotedStringsResponse() {
        assertChunkLineRejected(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                "1;a=\" \\\r;\t\"\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustRejectChunkExtensionsWithEscapedCarriageReturnSplitAcrossBuffersRequest() {
        // The bare CR is the last readable byte of a buffer, so the chunk line must be re-read once more
        // data arrives and still be rejected.
        assertChunkLineRejectedByteByByte(new HttpRequestDecoder(), CHUNKED_REQUEST_HEAD +
                "1;a=\" \\\r;\t\"\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustRejectChunkExtensionsWithEscapedCarriageReturnSplitAcrossBuffersResponse() {
        assertChunkLineRejectedByteByByte(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                "1;a=\" \\\r;\t\"\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustRejectBareCarriageReturnInChunkExtensionsRequest() {
        assertChunkLineRejected(new HttpRequestDecoder(), CHUNKED_REQUEST_HEAD +
                "0;\rX\r\n" +
                "\r\n");
    }

    @Test
    public void mustRejectBareCarriageReturnInChunkExtensionsResponse() {
        assertChunkLineRejected(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                "0;\rX\r\n" +
                "\r\n");
    }

    @Test
    public void mustRejectControlCharacterInChunkExtensionsRequest() {
        assertChunkLineRejected(new HttpRequestDecoder(), CHUNKED_REQUEST_HEAD +
                "1;\0a\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustRejectControlCharacterInChunkExtensionsResponse() {
        assertChunkLineRejected(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                "1;\0a\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustRejectChunkExtensionsWithoutValueRequest() {
        assertChunkLineRejected(new HttpRequestDecoder(), CHUNKED_REQUEST_HEAD +
                "1;a=\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustRejectChunkExtensionsWithoutValueResponse() {
        assertChunkLineRejected(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                "1;a=\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustParseChunkExtensionsWithObsTextInQuotedStringsRequest() {
        assertChunkLineAccepted(new HttpRequestDecoder(), CHUNKED_REQUEST_HEAD +
                "1;a=\"é\\ÿ\"\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustParseChunkExtensionsWithObsTextInQuotedStringsResponse() {
        assertChunkLineAccepted(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                "1;a=\"é\\ÿ\"\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustParseChunkExtensionsWithoutValueRequest() {
        assertChunkLineAccepted(new HttpRequestDecoder(), CHUNKED_REQUEST_HEAD +
                " 1 ;ext;\r\n" +
                "Y\r\n" +
                "0;last\r\n" +
                "\r\n");
    }

    @Test
    public void mustParseChunkExtensionsWithoutValueResponse() {
        assertChunkLineAccepted(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                " 1 ;ext;\r\n" +
                "Y\r\n" +
                "0;last\r\n" +
                "\r\n");
    }

    @Test
    public void mustParseChunkExtensionsWithQuotedStringsSplitAcrossBuffersRequest() {
        assertChunkLineAcceptedByteByByte(new HttpRequestDecoder(), CHUNKED_REQUEST_HEAD +
                "1;a=\" \\\";\t\";b=c\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void mustParseChunkExtensionsWithQuotedStringsSplitAcrossBuffersResponse() {
        assertChunkLineAcceptedByteByByte(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                "1;a=\" \\\";\t\";b=c\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Test
    public void lineLengthRestrictionMustNotApplyToChunkContentsRequest() {
        lineLengthRestrictionMustNotApplyToChunkContents(new HttpRequestDecoder(), "POST /one HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n");
    }

    @Test
    public void lineLengthRestrictionMustNotApplyToChunkContentsResponse() {
        lineLengthRestrictionMustNotApplyToChunkContents(new HttpResponseDecoder(), "HTTP/1.1 200 OK\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n");
    }

    @Test
    public void mustRejectChunkSizeWithNonHexadecimalCharactersRequest() {
        assertChunkLineRejected(new HttpRequestDecoder(), "POST /one HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "test\r\n\r\n" +
                "\r\n", NumberFormatException.class);
    }

    @Test
    public void mustRejectChunkSizeWithNonHexadecimalCharactersResponse() {
        assertChunkLineRejected(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                "test\r\n\r\n" +
                "\r\n", NumberFormatException.class);
    }

    @Test
    public void mustRejectChunkSizeWithNonHexadecimalCharactersBeforeChunkExtensionsRequest() {
        assertChunkLineRejected(new HttpRequestDecoder(), CHUNKED_REQUEST_HEAD +
                "1\0;a=b\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n", NumberFormatException.class);
    }

    @Test
    public void mustRejectChunkSizeWithNonHexadecimalCharactersBeforeChunkExtensionsResponse() {
        assertChunkLineRejected(new HttpResponseDecoder(), CHUNKED_RESPONSE_HEAD +
                "1\0;a=b\r\n" +
                "Y\r\n" +
                "0\r\n" +
                "\r\n", NumberFormatException.class);
    }

    private static void lineLengthRestrictionMustNotApplyToChunkContents(HttpMessageDecoder decoder, String head) {
        char[] chars = new char[10000];
        Arrays.fill(chars, 'a');
        String content = new String(chars);
        String messageStr = head +
                Integer.toHexString(chars.length) + "\r\n" +
                content + "\r\n" +
                "0\r\n\r\n";
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(decoder);
        embedder.offer(ChannelBuffers.copiedBuffer(messageStr, CharsetUtil.US_ASCII));
        HttpMessage message = (HttpMessage) embedder.poll();
        assertTrue(message.isChunked());
        int contentLength = 0;
        for (;;) {
            HttpChunk chunk = (HttpChunk) embedder.poll();
            assertNotNull(chunk);
            if (chunk.isLast()) {
                break;
            }
            contentLength += chunk.getContent().readableBytes();
        }
        assertEquals(chars.length, contentLength);
        assertFalse(embedder.finish()); // And there are no other chunks parsed.
    }

    private static void assertChunkLineAccepted(HttpMessageDecoder decoder, String messageStr) {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(decoder);
        embedder.offer(ChannelBuffers.copiedBuffer(messageStr, CharsetUtil.ISO_8859_1));
        assertChunkLineAccepted(embedder);
    }

    private static void assertChunkLineAcceptedByteByByte(HttpMessageDecoder decoder, String messageStr) {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(decoder);
        offerByteByByte(embedder, messageStr);
        assertChunkLineAccepted(embedder);
    }

    private static void offerByteByByte(DecoderEmbedder<Object> embedder, String messageStr) {
        ChannelBuffer buffer = ChannelBuffers.copiedBuffer(messageStr, CharsetUtil.ISO_8859_1);
        while (buffer.readable()) {
            embedder.offer(ChannelBuffers.wrappedBuffer(new byte[] { buffer.readByte() }));
        }
    }

    private static void assertChunkLineAccepted(DecoderEmbedder<Object> embedder) {
        HttpMessage message = (HttpMessage) embedder.poll();
        assertTrue(message.isChunked()); // We parse the headers just fine.
        assertEquals(HttpHeaders.Values.CHUNKED, message.getHeader(HttpHeaders.Names.TRANSFER_ENCODING));
        HttpChunk chunk = (HttpChunk) embedder.poll(); // And we parse the chunk.
        assertEquals("Y", chunk.getContent().toString(CharsetUtil.US_ASCII));
        assertTrue(embedder.poll() instanceof HttpChunkTrailer);
        assertFalse(embedder.finish()); // And there are no other chunks parsed.
    }

    private static void assertChunkLineRejected(HttpMessageDecoder decoder, String messageStr) {
        assertChunkLineRejected(decoder, messageStr, IllegalArgumentException.class);
    }

    private static void assertChunkLineRejected(
            HttpMessageDecoder decoder, String messageStr, Class<? extends Exception> expectedCause) {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(decoder);
        try {
            embedder.offer(ChannelBuffers.copiedBuffer(messageStr, CharsetUtil.ISO_8859_1));
            fail("Expected CodecEmbedderException");
        } catch (CodecEmbedderException e) {
            assertEquals(expectedCause, e.getCause().getClass());
        }
        assertOnlyMessageHeadDecoded(embedder);
    }

    private static void assertChunkLineRejectedByteByByte(HttpMessageDecoder decoder, String messageStr) {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(decoder);
        try {
            offerByteByByte(embedder, messageStr);
            fail("Expected CodecEmbedderException");
        } catch (CodecEmbedderException e) {
            assertEquals(IllegalArgumentException.class, e.getCause().getClass());
        }
        assertOnlyMessageHeadDecoded(embedder);
    }

    private static void assertOnlyMessageHeadDecoded(DecoderEmbedder<Object> embedder) {
        HttpMessage message = (HttpMessage) embedder.poll();
        assertTrue(message.isChunked()); // We parse the headers just fine.
        assertEquals(HttpHeaders.Values.CHUNKED, message.getHeader(HttpHeaders.Names.TRANSFER_ENCODING));
        // The invalid chunk line produced no chunk, and nothing after it (e.g. a smuggled message) was decoded.
        assertNull(embedder.poll());
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

    private static void testInvalidRequestHeaders0(ChannelBuffer requestBuffer) {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpRequestDecoder());
        try {
            embedder.offer(requestBuffer);
            fail("Expected CodecEmbedderException");
        } catch (CodecEmbedderException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    private static void testInvalidResponseHeaders0(ChannelBuffer responseBuffer) {
        DecoderEmbedder<Object> embedder = new DecoderEmbedder<Object>(new HttpResponseDecoder());
        try {
            embedder.offer(responseBuffer);
            fail("Expected CodecEmbedderException");
        } catch (CodecEmbedderException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }
}
