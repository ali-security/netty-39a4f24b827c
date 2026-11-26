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
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.jboss.netty.handler.codec.replay.ReplayingDecoder;

import java.util.BitSet;
import java.util.List;

/**
 * Decodes {@link ChannelBuffer}s into {@link HttpMessage}s and
 * {@link HttpChunk}s.
 *
 * <h3>Parameters that prevents excessive memory consumption</h3>
 * <table border="1">
 * <tr>
 * <th>Name</th><th>Meaning</th>
 * </tr>
 * <tr>
 * <td>{@code maxInitialLineLength}</td>
 * <td>The maximum length of the initial line
 *     (e.g. {@code "GET / HTTP/1.0"} or {@code "HTTP/1.0 200 OK"})
 *     If the length of the initial line exceeds this value, a
 *     {@link TooLongFrameException} will be raised.</td>
 * </tr>
 * <tr>
 * <td>{@code maxHeaderSize}</td>
 * <td>The maximum length of all headers.  If the sum of the length of each
 *     header exceeds this value, a {@link TooLongFrameException} will be raised.</td>
 * </tr>
 * <tr>
 * <td>{@code maxChunkSize}</td>
 * <td>The maximum length of the content or each chunk.  If the content length
 *     (or the length of each chunk) exceeds this value, the content or chunk
 *     will be split into multiple {@link HttpChunk}s whose length is
 *     {@code maxChunkSize} at maximum.</td>
 * </tr>
 * </table>
 *
 * <h3>Chunked Content</h3>
 *
 * If the content of an HTTP message is greater than {@code maxChunkSize} or
 * the transfer encoding of the HTTP message is 'chunked', this decoder
 * generates one {@link HttpMessage} instance and its following
 * {@link HttpChunk}s per single HTTP message to avoid excessive memory
 * consumption. For example, the following HTTP message:
 * <pre>
 * GET / HTTP/1.1
 * Transfer-Encoding: chunked
 *
 * 1a
 * abcdefghijklmnopqrstuvwxyz
 * 10
 * 1234567890abcdef
 * 0
 * Content-MD5: ...
 * <i>[blank line]</i>
 * </pre>
 * triggers {@link HttpRequestDecoder} to generate 4 objects:
 * <ol>
 * <li>An {@link HttpRequest} whose {@link HttpMessage#isChunked() chunked}
 *     property is {@code true},</li>
 * <li>The first {@link HttpChunk} whose content is {@code 'abcdefghijklmnopqrstuvwxyz'},</li>
 * <li>The second {@link HttpChunk} whose content is {@code '1234567890abcdef'}, and</li>
 * <li>An {@link HttpChunkTrailer} which marks the end of the content.</li>
 * </ol>
 *
 * If you prefer not to handle {@link HttpChunk}s by yourself for your
 * convenience, insert {@link HttpChunkAggregator} after this decoder in the
 * {@link ChannelPipeline}.  However, please note that your server might not
 * be as memory efficient as without the aggregator.
 *
 * <h3>Extensibility</h3>
 *
 * Please note that this decoder is designed to be extended to implement
 * a protocol derived from HTTP, such as
 * <a href="http://en.wikipedia.org/wiki/Real_Time_Streaming_Protocol">RTSP</a> and
 * <a href="http://en.wikipedia.org/wiki/Internet_Content_Adaptation_Protocol">ICAP</a>.
 * To implement the decoder of such a derived protocol, extend this class and
 * implement all abstract methods properly.
 * @apiviz.landmark
 */
public abstract class HttpMessageDecoder extends ReplayingDecoder<HttpMessageDecoder.State> {

    /*
     * States and octet classes used by checkChunkExtensions(String) to validate the chunk start line, that is
     * the chunk size and chunk extensions until the CR LF pair. See RFC 9112 section 7.1 and CVE-2026-33870
     * (GHSA-pwqr-wmgm-9rr8), a request smuggling attack through line breaks hidden in chunk extensions:
     *
     *   chunk          = chunk-size [ chunk-ext ] CRLF
     *                    chunk-data CRLF
     *   chunk-size     = 1*HEXDIG
     *   last-chunk     = 1*("0") [ chunk-ext ] CRLF
     *   chunk-ext      = *( BWS ";" BWS chunk-ext-name
     *                       [ BWS "=" BWS chunk-ext-val ] )
     *   chunk-ext-name = token
     *   chunk-ext-val  = token / quoted-string
     *   quoted-string  = DQUOTE *( qdtext / quoted-pair ) DQUOTE
     *   qdtext         = HTAB / SP / %x21 / %x23-5B / %x5D-7E / obs-text
     *   quoted-pair    = "\" ( HTAB / SP / VCHAR / obs-text )
     *   obs-text       = %x80-FF
     *   BWS            = *( SP / HTAB )
     *   VCHAR          = %x21-7E
     */
    private static final int CHUNK_LINE_SIZE = 0;
    private static final int CHUNK_LINE_EXT_NAME = 1;
    private static final int CHUNK_LINE_EXT_VAL_START = 2;
    private static final int CHUNK_LINE_EXT_VAL_QUOTED = 3;
    private static final int CHUNK_LINE_EXT_VAL_QUOTED_ESCAPE = 4;
    private static final int CHUNK_LINE_EXT_VAL_QUOTED_END = 5;
    private static final int CHUNK_LINE_EXT_VAL_TOKEN = 6;

    private static final BitSet CHUNK_SIZE_CHARS = new BitSet(256);
    private static final BitSet CHUNK_EXT_NAME_CHARS = new BitSet(256);
    private static final BitSet CHUNK_EXT_VAL_TOKEN_START_CHARS = new BitSet(256);
    private static final BitSet CHUNK_EXT_VAL_TOKEN_CHARS = new BitSet(256);
    private static final BitSet CHUNK_EXT_VAL_QDTEXT_CHARS = new BitSet(256);
    private static final BitSet CHUNK_EXT_VAL_QUOTED_PAIR_CHARS = new BitSet(256);

    static {
        final String delimiters = "(),/:<=>?@[\\]{}";

        setChunkLineChars(CHUNK_SIZE_CHARS, "0123456789abcdefABCDEF \t", true);

        CHUNK_EXT_NAME_CHARS.set(0x21, 0x7E + 1);
        setChunkLineChars(CHUNK_EXT_NAME_CHARS, " \t", true);
        setChunkLineChars(CHUNK_EXT_NAME_CHARS, delimiters, false);

        CHUNK_EXT_VAL_TOKEN_START_CHARS.set(0x21, 0x7E + 1);
        setChunkLineChars(CHUNK_EXT_VAL_TOKEN_START_CHARS, delimiters + '"', false);

        CHUNK_EXT_VAL_TOKEN_CHARS.set(0x21, 0x7E + 1);
        setChunkLineChars(CHUNK_EXT_VAL_TOKEN_CHARS, delimiters + ';', false);

        setChunkLineChars(CHUNK_EXT_VAL_QDTEXT_CHARS, "\t !", true);
        CHUNK_EXT_VAL_QDTEXT_CHARS.set(0x23, 0x5B + 1);
        CHUNK_EXT_VAL_QDTEXT_CHARS.set(0x5D, 0x7E + 1);
        CHUNK_EXT_VAL_QDTEXT_CHARS.set(0x80, 0xFF + 1);

        setChunkLineChars(CHUNK_EXT_VAL_QUOTED_PAIR_CHARS, "\t ", true);
        CHUNK_EXT_VAL_QUOTED_PAIR_CHARS.set(0x21, 0x7E + 1);
        CHUNK_EXT_VAL_QUOTED_PAIR_CHARS.set(0x80, 0xFF + 1);
    }

    private final int maxInitialLineLength;
    private final int maxHeaderSize;
    private final int maxChunkSize;
    private final boolean allowDuplicateContentLengths;
    private HttpMessage message;
    private ChannelBuffer content;
    private long chunkSize;
    private int headerSize;
    private int contentRead;
    // Number of leading control/whitespace characters skipped while looking for the next
    // initial line. Tracked across decode invocations to enforce the maxInitialLineLength
    // budget and prevent a DoS where an unbounded run of control characters keeps the
    // decoder busy forever. See https://github.com/netty/netty/issues/10111 (sonatype-2020-0103).
    private int skippedControlChars;

    /**
     * The internal state of {@link HttpMessageDecoder}.
     * <em>Internal use only</em>.
     * @apiviz.exclude
     */
    protected enum State {
        SKIP_CONTROL_CHARS,
        READ_INITIAL,
        READ_HEADER,
        READ_VARIABLE_LENGTH_CONTENT,
        READ_VARIABLE_LENGTH_CONTENT_AS_CHUNKS,
        READ_FIXED_LENGTH_CONTENT,
        READ_FIXED_LENGTH_CONTENT_AS_CHUNKS,
        READ_CHUNK_SIZE,
        READ_CHUNKED_CONTENT,
        READ_CHUNKED_CONTENT_AS_CHUNKS,
        READ_CHUNK_DELIMITER,
        READ_CHUNK_FOOTER
    }

    /**
     * Creates a new instance with the default
     * {@code maxInitialLineLength (4096}}, {@code maxHeaderSize (8192)}, and
     * {@code maxChunkSize (8192)}.
     */
    protected HttpMessageDecoder() {
        this(4096, 8192, 8192, false);
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    protected HttpMessageDecoder(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
        this(maxInitialLineLength, maxHeaderSize, maxChunkSize, false);
    }

    /**
     * Creates a new instance with the specified parameters.
     *
     * @param allowDuplicateContentLengths
     *        {@code true} if multiple, identical-value {@code Content-Length}
     *        values should be replaced by a single valid {@code Content-Length}
     *        field instead of rejecting the message as invalid
     */
    protected HttpMessageDecoder(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize,
            boolean allowDuplicateContentLengths) {

        super(State.SKIP_CONTROL_CHARS, true);

        if (maxInitialLineLength <= 0) {
            throw new IllegalArgumentException(
                    "maxInitialLineLength must be a positive integer: " +
                    maxInitialLineLength);
        }
        if (maxHeaderSize <= 0) {
            throw new IllegalArgumentException(
                    "maxHeaderSize must be a positive integer: " +
                    maxHeaderSize);
        }
        if (maxChunkSize < 0) {
            throw new IllegalArgumentException(
                    "maxChunkSize must be a positive integer: " +
                    maxChunkSize);
        }
        this.maxInitialLineLength = maxInitialLineLength;
        this.maxHeaderSize = maxHeaderSize;
        this.maxChunkSize = maxChunkSize;
        this.allowDuplicateContentLengths = allowDuplicateContentLengths;
    }

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, ChannelBuffer buffer, State state) throws Exception {
        switch (state) {
        case SKIP_CONTROL_CHARS: {
            try {
                skipControlCharacters(buffer);
                checkpoint(State.READ_INITIAL);
            } finally {
                checkpoint();
            }
        }
        case READ_INITIAL: {
            String[] initialLine = splitInitialLine(
                    readLine(buffer, maxInitialLineLength, skippedControlChars));
            if (initialLine.length < 3) {
                // Invalid initial line - ignore.
                skippedControlChars = 0;
                checkpoint(State.SKIP_CONTROL_CHARS);
                return null;
            }

            message = createMessage(initialLine);
            skippedControlChars = 0;
            checkpoint(State.READ_HEADER);
        }
        case READ_HEADER: {
            State nextState = readHeaders(buffer);
            checkpoint(nextState);
            if (nextState == State.READ_CHUNK_SIZE) {
                // Chunked encoding
                message.setChunked(true);
                // Generate HttpMessage first.  HttpChunks will follow.
                return message;
            }
            if (nextState == State.SKIP_CONTROL_CHARS) {
                // No content is expected.
                // Remove the headers which are not supposed to be present not
                // to confuse subsequent handlers.
                message.removeHeader(HttpHeaders.Names.TRANSFER_ENCODING);
                return message;
            }
            long contentLength = HttpHeaders.getContentLength(message, -1);
            if (contentLength == 0 || contentLength == -1 && isDecodingRequest()) {
                content = ChannelBuffers.EMPTY_BUFFER;
                return reset();
            }

            switch (nextState) {
            case READ_FIXED_LENGTH_CONTENT:
                if (contentLength > maxChunkSize || HttpHeaders.is100ContinueExpected(message)) {
                    // Generate HttpMessage first.  HttpChunks will follow.
                    checkpoint(State.READ_FIXED_LENGTH_CONTENT_AS_CHUNKS);
                    message.setChunked(true);
                    // chunkSize will be decreased as the READ_FIXED_LENGTH_CONTENT_AS_CHUNKS
                    // state reads data chunk by chunk.
                    chunkSize = HttpHeaders.getContentLength(message, -1);
                    return message;
                }
                break;
            case READ_VARIABLE_LENGTH_CONTENT:
                if (buffer.readableBytes() > maxChunkSize || HttpHeaders.is100ContinueExpected(message)) {
                    // Generate HttpMessage first.  HttpChunks will follow.
                    checkpoint(State.READ_VARIABLE_LENGTH_CONTENT_AS_CHUNKS);
                    message.setChunked(true);
                    return message;
                }
                break;
            default:
                throw new IllegalStateException("Unexpected state: " + nextState);
            }
            // We return null here, this forces decode to be called again where we will decode the content
            return null;
        }
        case READ_VARIABLE_LENGTH_CONTENT: {
            int toRead = actualReadableBytes();
            if (toRead > maxChunkSize) {
                toRead = maxChunkSize;
            }
            if (!message.isChunked()) {
                message.setChunked(true);
                return new Object[] {message, new DefaultHttpChunk(buffer.readBytes(toRead))};
            } else {
                return new DefaultHttpChunk(buffer.readBytes(toRead));
            }
        }
        case READ_VARIABLE_LENGTH_CONTENT_AS_CHUNKS: {
            // Keep reading data as a chunk until the end of connection is reached.
            int toRead = actualReadableBytes();
            if (toRead > maxChunkSize) {
                toRead = maxChunkSize;
            }
            HttpChunk chunk = new DefaultHttpChunk(buffer.readBytes(toRead));

            if (!buffer.readable()) {
                // Reached to the end of the connection.
                reset();
                if (!chunk.isLast()) {
                    // Append the last chunk.
                    return new Object[] { chunk, HttpChunk.LAST_CHUNK };
                }
            }
            return chunk;
        }
        case READ_FIXED_LENGTH_CONTENT: {
            return readFixedLengthContent(buffer);
        }
        case READ_FIXED_LENGTH_CONTENT_AS_CHUNKS: {
            long chunkSize = this.chunkSize;
            int readLimit = actualReadableBytes();

            // Check if the buffer is readable first as we use the readable byte count
            // to create the HttpChunk. This is needed as otherwise we may end up with
            // create a HttpChunk instance that contains an empty buffer and so is
            // handled like it is the last HttpChunk.
            //
            // See https://github.com/netty/netty/issues/433
            if (readLimit == 0) {
                return null;
            }

            int toRead = readLimit;
            if (toRead > maxChunkSize) {
                toRead = maxChunkSize;
            }
            if (toRead > chunkSize) {
                toRead = (int) chunkSize;
            }
            HttpChunk chunk = new DefaultHttpChunk(buffer.readBytes(toRead));
            if (chunkSize > toRead) {
                chunkSize -= toRead;
            } else {
                chunkSize = 0;
            }
            this.chunkSize = chunkSize;

            if (chunkSize == 0) {
                // Read all content.
                reset();
                if (!chunk.isLast()) {
                    // Append the last chunk.
                    return new Object[] { chunk, HttpChunk.LAST_CHUNK };
                }
            }
            return chunk;
        }
        /**
         * everything else after this point takes care of reading chunked content. basically, read chunk size,
         * read chunk, read and ignore the CRLF and repeat until 0
         */
        case READ_CHUNK_SIZE: {
            String line = readChunkSizeLine(buffer, maxInitialLineLength);
            checkChunkExtensions(line);
            int chunkSize = getChunkSize(line);
            this.chunkSize = chunkSize;
            if (chunkSize == 0) {
                checkpoint(State.READ_CHUNK_FOOTER);
                return null;
            } else if (chunkSize > maxChunkSize) {
                // A chunk is too large. Split them into multiple chunks again.
                checkpoint(State.READ_CHUNKED_CONTENT_AS_CHUNKS);
            } else {
                checkpoint(State.READ_CHUNKED_CONTENT);
            }
        }
        case READ_CHUNKED_CONTENT: {
            assert chunkSize <= Integer.MAX_VALUE;
            HttpChunk chunk = new DefaultHttpChunk(buffer.readBytes((int) chunkSize));
            checkpoint(State.READ_CHUNK_DELIMITER);
            return chunk;
        }
        case READ_CHUNKED_CONTENT_AS_CHUNKS: {
            assert chunkSize <= Integer.MAX_VALUE;
            int chunkSize = (int) this.chunkSize;
            int readLimit = actualReadableBytes();

            // Check if the buffer is readable first as we use the readable byte count
            // to create the HttpChunk. This is needed as otherwise we may end up with
            // create a HttpChunk instance that contains an empty buffer and so is
            // handled like it is the last HttpChunk.
            //
            // See https://github.com/netty/netty/issues/433
            if (readLimit == 0) {
                return null;
            }

            int toRead = chunkSize;
            if (toRead > maxChunkSize) {
                toRead = maxChunkSize;
            }
            if (toRead > readLimit) {
                toRead = readLimit;
            }
            HttpChunk chunk = new DefaultHttpChunk(buffer.readBytes(toRead));
            if (chunkSize > toRead) {
                chunkSize -= toRead;
            } else {
                chunkSize = 0;
            }
            this.chunkSize = chunkSize;

            if (chunkSize == 0) {
                // Read all content.
                checkpoint(State.READ_CHUNK_DELIMITER);
            }

            if (!chunk.isLast()) {
                return chunk;
            }
        }
        case READ_CHUNK_DELIMITER: {
            for (;;) {
                byte next = buffer.readByte();
                if (next == HttpConstants.CR) {
                    if (buffer.readByte() == HttpConstants.LF) {
                        checkpoint(State.READ_CHUNK_SIZE);
                        return null;
                    }
                } else if (next == HttpConstants.LF) {
                    checkpoint(State.READ_CHUNK_SIZE);
                    return null;
                }
            }
        }
        case READ_CHUNK_FOOTER: {
            HttpChunkTrailer trailer = readTrailingHeaders(buffer);
            if (maxChunkSize == 0) {
                // Chunked encoding disabled.
                return reset();
            } else {
                reset();
                // The last chunk, which is empty
                return trailer;
            }
        }
        default: {
            throw new Error("Shouldn't reach here.");
        }
        }
    }

    protected boolean isContentAlwaysEmpty(HttpMessage msg) {
        if (msg instanceof HttpResponse) {
            HttpResponse res = (HttpResponse) msg;
            int code = res.getStatus().getCode();

            // Correctly handle return codes of 1xx.
            //
            // See:
            //     - http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html Section 4.4
            //     - https://github.com/netty/netty/issues/222
            if (code >= 100 && code < 200) {
                if (code == 101 && !res.containsHeader(HttpHeaders.Names.SEC_WEBSOCKET_ACCEPT)) {
                    // It's Hixie 76 websocket handshake response
                    return false;
                }
                return true;
            }

            switch (code) {
            case 204: case 205: case 304:
                return true;
            }
        }
        return false;
    }

    private Object reset() {
        HttpMessage message = this.message;
        ChannelBuffer content = this.content;

        if (content != null) {
            message.setContent(content);
            this.content = null;
        }
        this.message = null;

        skippedControlChars = 0;
        checkpoint(State.SKIP_CONTROL_CHARS);
        return message;
    }

    private void skipControlCharacters(ChannelBuffer buffer) throws TooLongFrameException {
        for (;;) {
            char c = (char) buffer.readUnsignedByte();
            if (!Character.isISOControl(c) &&
                !Character.isWhitespace(c)) {
                buffer.readerIndex(buffer.readerIndex() - 1);
                break;
            }
            // Count the skipped char against the initial line budget so that an
            // unbounded run of control characters cannot keep the decoder busy
            // forever. See https://github.com/netty/netty/issues/10111.
            if (++skippedControlChars > maxInitialLineLength) {
                throw new TooLongFrameException(
                        "An HTTP line is larger than " + maxInitialLineLength + " bytes.");
            }
        }
    }

    private Object readFixedLengthContent(ChannelBuffer buffer) {
        //we have a content-length so we just read the correct number of bytes
        long length = HttpHeaders.getContentLength(message, -1);
        assert length <= Integer.MAX_VALUE;
        int toRead = (int) length - contentRead;
        if (toRead > actualReadableBytes()) {
            toRead = actualReadableBytes();
        }
        contentRead += toRead;
        if (length < contentRead) {
            if (!message.isChunked()) {
                message.setChunked(true);
                return new Object[] {message, new DefaultHttpChunk(buffer.readBytes(toRead))};
            } else {
                return new DefaultHttpChunk(buffer.readBytes(toRead));
            }
        }
        if (content == null) {
            content = buffer.readBytes((int) length);
        } else {
            content.writeBytes(buffer, (int) length);
        }
        return reset();
    }

    private State readHeaders(ChannelBuffer buffer) throws TooLongFrameException {
        headerSize = 0;
        final HttpMessage message = this.message;
        String line = readHeader(buffer);
        String name = null;
        String value = null;
        if (line.length() != 0) {
            message.clearHeaders();
            do {
                char firstChar = line.charAt(0);
                if (name != null && (firstChar == ' ' || firstChar == '\t')) {
                    value = value + ' ' + line.trim();
                } else {
                    if (name != null) {
                        message.addHeader(name, value);
                    }
                    String[] header = splitHeader(line);
                    name = header[0];
                    value = header[1];
                }

                line = readHeader(buffer);
            } while (line.length() != 0);

            // Add the last header.
            if (name != null) {
                message.addHeader(name, value);
            }
        }

        State nextState;

        List<String> contentLengthFields = message.getHeaders(HttpHeaders.Names.CONTENT_LENGTH);
        int contentLengthValuesCount = contentLengthFields.size();

        if (contentLengthValuesCount > 0) {
            HttpVersion version = message.getProtocolVersion();
            boolean isHttp10OrEarlier = version.getMajorVersion() < 1 || (version.getMajorVersion() == 1
                    && version.getMinorVersion() == 0);
            // Guard against multiple Content-Length headers as stated in
            // https://tools.ietf.org/html/rfc7230#section-3.3.2:
            long contentLength = HttpCodecUtil.normalizeAndGetContentLength(contentLengthFields,
                    isHttp10OrEarlier, allowDuplicateContentLengths);
            if (contentLength != -1) {
                message.setHeader(HttpHeaders.Names.CONTENT_LENGTH, contentLength);
            }
        }

        if (isContentAlwaysEmpty(message)) {
            nextState = State.SKIP_CONTROL_CHARS;
        } else if (message.isChunked()) {
            // HttpMessage.isChunked() returns true when either:
            // 1) HttpMessage.setChunked(true) was called or
            // 2) 'Transfer-Encoding' is 'chunked'.
            // Because this decoder did not call HttpMessage.setChunked(true)
            // yet, HttpMessage.isChunked() should return true only when
            // 'Transfer-Encoding' is 'chunked'.
            if (contentLengthValuesCount > 0 && HttpVersion.HTTP_1_1.equals(message.getProtocolVersion())) {
                handleTransferEncodingChunkedWithContentLength(message);
            }
            nextState = State.READ_CHUNK_SIZE;
        } else if (HttpHeaders.getContentLength(message, -1) >= 0) {
            nextState = State.READ_FIXED_LENGTH_CONTENT;
        } else {
            nextState = State.READ_VARIABLE_LENGTH_CONTENT;
        }
        return nextState;
    }

    /**
     * Invoked when a message with both a "Transfer-Encoding: chunked" and a "Content-Length" header field is detected.
     * The default behavior is to <i>remove</i> the Content-Length field, but this method could be overridden
     * to change the behavior (to, e.g., throw an exception and produce an invalid message).
     * <p>
     * See: https://tools.ietf.org/html/rfc7230#section-3.3.3
     * <pre>
     *     If a message is received with both a Transfer-Encoding and a
     *     Content-Length header field, the Transfer-Encoding overrides the
     *     Content-Length.  Such a message might indicate an attempt to
     *     perform request smuggling (Section 9.5) or response splitting
     *     (Section 9.4) and ought to be handled as an error.  A sender MUST
     *     remove the received Content-Length field prior to forwarding such
     *     a message downstream.
     * </pre>
     * Also see:
     * https://github.com/apache/tomcat/blob/b693d7c1981fa7f51e58bc8c8e72e3fe80b7b773/
     * java/org/apache/coyote/http11/Http11Processor.java#L747-L755
     * https://github.com/nginx/nginx/blob/0ad4393e30c119d250415cb769e3d8bc8dce5186/
     * src/http/ngx_http_request.c#L1946-L1953
     */
    protected void handleTransferEncodingChunkedWithContentLength(HttpMessage message) {
        message.removeHeader(HttpHeaders.Names.CONTENT_LENGTH);
    }

    private HttpChunkTrailer readTrailingHeaders(ChannelBuffer buffer) throws TooLongFrameException {
        headerSize = 0;
        String line = readHeader(buffer);
        String lastHeader = null;
        if (line.length() != 0) {
            HttpChunkTrailer trailer = new DefaultHttpChunkTrailer();
            do {
                char firstChar = line.charAt(0);
                if (lastHeader != null && (firstChar == ' ' || firstChar == '\t')) {
                    List<String> current = trailer.getHeaders(lastHeader);
                    if (!current.isEmpty()) {
                        int lastPos = current.size() - 1;
                        String newString = current.get(lastPos) + line.trim();
                        current.set(lastPos, newString);
                    } else {
                        // Content-Length, Transfer-Encoding, or Trailer
                    }
                } else {
                    String[] header = splitHeader(line);
                    String name = header[0];
                    if (!name.equalsIgnoreCase(HttpHeaders.Names.CONTENT_LENGTH) &&
                        !name.equalsIgnoreCase(HttpHeaders.Names.TRANSFER_ENCODING) &&
                        !name.equalsIgnoreCase(HttpHeaders.Names.TRAILER)) {
                        trailer.addHeader(name, header[1]);
                    }
                    lastHeader = name;
                }

                line = readHeader(buffer);
            } while (line.length() != 0);

            return trailer;
        }

        return HttpChunk.LAST_CHUNK;
    }

    private String readHeader(ChannelBuffer buffer) throws TooLongFrameException {
        StringBuilder sb = new StringBuilder(64);
        int headerSize = this.headerSize;

        loop:
        for (;;) {
            char nextByte = (char) buffer.readByte();
            headerSize ++;

            switch (nextByte) {
            case HttpConstants.CR:
                nextByte = (char) buffer.readByte();
                headerSize ++;
                if (nextByte == HttpConstants.LF) {
                    break loop;
                }
                break;
            case HttpConstants.LF:
                break loop;
            }

            // Abort decoding if the header part is too large.
            if (headerSize >= maxHeaderSize) {
                // TODO: Respond with Bad Request and discard the traffic
                //    or close the connection.
                //       No need to notify the upstream handlers - just log.
                //       If decoding a response, just throw an exception.
                throw new TooLongFrameException(
                        "HTTP header is larger than " +
                        maxHeaderSize + " bytes.");
            }

            sb.append(nextByte);
        }

        this.headerSize = headerSize;
        return sb.toString();
    }

    protected abstract boolean isDecodingRequest();
    protected abstract HttpMessage createMessage(String[] initialLine) throws Exception;

    private static int getChunkSize(String hex) {
        hex = hex.trim();
        for (int i = 0; i < hex.length(); i ++) {
            char c = hex.charAt(i);
            if (c == ';' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                hex = hex.substring(0, i);
                break;
            }
        }

        return Integer.parseInt(hex, 16);
    }

    /**
     * Validates the chunk extensions of a chunk start line against the grammar of RFC 9112 section 7.1, so
     * that a line break, a bare CR or any other control character hidden in a chunk extension (for example
     * inside a quoted-string) is rejected instead of being interpreted differently than by other HTTP
     * implementations. See CVE-2026-33870 (GHSA-pwqr-wmgm-9rr8).
     *
     * @throws NumberFormatException if the chunk size before the chunk extensions is invalid
     * @throws IllegalArgumentException if the chunk extensions are invalid
     */
    private static void checkChunkExtensions(String line) {
        if (line.indexOf(';') == -1) {
            return;
        }
        int state = CHUNK_LINE_SIZE;
        for (int i = 0; i < line.length(); i ++) {
            // readChunkSizeLine() stores every octet as a (sign-extended) char, so this restores the octet.
            state = nextChunkLineState(state, line.charAt(i) & 0xFF);
        }
        switch (state) {
        case CHUNK_LINE_EXT_VAL_START:
        case CHUNK_LINE_EXT_VAL_QUOTED:
        case CHUNK_LINE_EXT_VAL_QUOTED_ESCAPE:
            throw new IllegalArgumentException("Invalid chunk extension");
        }
    }

    private static int nextChunkLineState(int state, int octet) {
        switch (state) {
        case CHUNK_LINE_SIZE:
            if (CHUNK_SIZE_CHARS.get(octet)) {
                return CHUNK_LINE_SIZE;
            }
            if (octet == ';') {
                return CHUNK_LINE_EXT_NAME;
            }
            throw new NumberFormatException("Invalid chunk size");
        case CHUNK_LINE_EXT_NAME:
            if (CHUNK_EXT_NAME_CHARS.get(octet)) {
                return CHUNK_LINE_EXT_NAME;
            }
            if (octet == '=') {
                return CHUNK_LINE_EXT_VAL_START;
            }
            break;
        case CHUNK_LINE_EXT_VAL_START:
            if (octet == ' ' || octet == '\t') {
                return CHUNK_LINE_EXT_VAL_START;
            }
            if (octet == '"') {
                return CHUNK_LINE_EXT_VAL_QUOTED;
            }
            if (CHUNK_EXT_VAL_TOKEN_START_CHARS.get(octet)) {
                return CHUNK_LINE_EXT_VAL_TOKEN;
            }
            break;
        case CHUNK_LINE_EXT_VAL_QUOTED:
            if (octet == '\\') {
                return CHUNK_LINE_EXT_VAL_QUOTED_ESCAPE;
            }
            if (octet == '"') {
                return CHUNK_LINE_EXT_VAL_QUOTED_END;
            }
            if (CHUNK_EXT_VAL_QDTEXT_CHARS.get(octet)) {
                return CHUNK_LINE_EXT_VAL_QUOTED;
            }
            break;
        case CHUNK_LINE_EXT_VAL_QUOTED_ESCAPE:
            if (CHUNK_EXT_VAL_QUOTED_PAIR_CHARS.get(octet)) {
                return CHUNK_LINE_EXT_VAL_QUOTED;
            }
            break;
        case CHUNK_LINE_EXT_VAL_QUOTED_END:
            if (octet == ' ' || octet == '\t') {
                return CHUNK_LINE_EXT_VAL_QUOTED_END;
            }
            if (octet == ';') {
                return CHUNK_LINE_EXT_NAME;
            }
            break;
        case CHUNK_LINE_EXT_VAL_TOKEN:
            if (CHUNK_EXT_VAL_TOKEN_CHARS.get(octet)) {
                return CHUNK_LINE_EXT_VAL_TOKEN;
            }
            if (octet == ';') {
                return CHUNK_LINE_EXT_NAME;
            }
            break;
        default:
            throw new Error("Shouldn't reach here.");
        }
        throw new IllegalArgumentException("Invalid chunk extension");
    }

    private static void setChunkLineChars(BitSet set, String chars, boolean value) {
        for (int i = 0; i < chars.length(); i ++) {
            set.set(chars.charAt(i), value);
        }
    }

    /**
     * Reads the chunk start line (the chunk size and chunk extensions). Unlike
     * {@link #readLine(ChannelBuffer, int, int)}, a CR which is not followed by a LF is kept in the
     * returned line, together with the octet following it, so that {@link #checkChunkExtensions(String)}
     * rejects it instead of both octets being silently dropped.
     */
    private static String readChunkSizeLine(ChannelBuffer buffer, int maxLineLength)
            throws TooLongFrameException {
        StringBuilder sb = new StringBuilder(64);
        int lineLength = 0;
        while (true) {
            byte nextByte = buffer.readByte();
            if (nextByte == HttpConstants.CR) {
                if (buffer.readByte() == HttpConstants.LF) {
                    return sb.toString();
                }
                // A bare CR: keep it and read the octet following it again as part of the line.
                buffer.readerIndex(buffer.readerIndex() - 1);
            } else if (nextByte == HttpConstants.LF) {
                return sb.toString();
            }
            if (lineLength >= maxLineLength) {
                throw new TooLongFrameException(
                        "An HTTP line is larger than " + maxLineLength +
                        " bytes.");
            }
            lineLength ++;
            sb.append((char) nextByte);
        }
    }

    /**
     * Reads a single CRLF/LF-terminated line, enforcing that the total number of consumed bytes
     * (including the {@code initialLength} already accounted for by the caller) does not exceed
     * {@code maxLineLength}. The {@code initialLength} hook lets the initial-line decoder share the
     * {@code maxInitialLineLength} budget with any leading control characters skipped by
     * {@link #skipControlCharacters(ChannelBuffer)} (see https://github.com/netty/netty/issues/10111).
     */
    private static String readLine(ChannelBuffer buffer, int maxLineLength, int initialLength)
            throws TooLongFrameException {
        StringBuilder sb = new StringBuilder(64);
        int lineLength = initialLength;
        while (true) {
            byte nextByte = buffer.readByte();
            if (nextByte == HttpConstants.CR) {
                nextByte = buffer.readByte();
                if (nextByte == HttpConstants.LF) {
                    return sb.toString();
                }
            } else if (nextByte == HttpConstants.LF) {
                return sb.toString();
            } else {
                if (lineLength >= maxLineLength) {
                    // TODO: Respond with Bad Request and discard the traffic
                    //    or close the connection.
                    //       No need to notify the upstream handlers - just log.
                    //       If decoding a response, just throw an exception.
                    throw new TooLongFrameException(
                            "An HTTP line is larger than " + maxLineLength +
                            " bytes.");
                }
                lineLength ++;
                sb.append((char) nextByte);
            }
        }
    }

    private static String[] splitInitialLine(String sb) {
        int aStart;
        int aEnd;
        int bStart;
        int bEnd;
        int cStart;
        int cEnd;

        aStart = findNonWhitespace(sb, 0);
        aEnd = findWhitespace(sb, aStart);

        bStart = findNonWhitespace(sb, aEnd);
        bEnd = findWhitespace(sb, bStart);

        cStart = findNonWhitespace(sb, bEnd);
        cEnd = findEndOfString(sb);

        return new String[] {
                sb.substring(aStart, aEnd),
                sb.substring(bStart, bEnd),
                cStart < cEnd? sb.substring(cStart, cEnd) : "" };
    }

    private String[] splitHeader(String sb) {
        final int length = sb.length();
        int nameStart;
        int nameEnd;
        int colonEnd;
        int valueStart;
        int valueEnd;

        nameStart = findNonWhitespace(sb, 0);
        for (nameEnd = nameStart; nameEnd < length; nameEnd ++) {
            char ch = sb.charAt(nameEnd);
            // https://tools.ietf.org/html/rfc7230#section-3.2.4
            //
            // No whitespace is allowed between the header field-name and colon. When
            // decoding a request we keep the whitespace as part of the name so that
            // the strict header-name validation in HttpCodecUtil rejects it (400 Bad
            // Request); when decoding a response we "skip" the whitespace to stay
            // interoperable with non-compliant servers. See GHSA-xx5j (CVE-2019-16869).
            if (ch == ':' ||
                    (!isDecodingRequest() && Character.isWhitespace(ch))) {
                break;
            }
        }

        if (nameEnd == length) {
            // There was no colon present at all.
            throw new IllegalArgumentException("No colon found");
        }

        for (colonEnd = nameEnd; colonEnd < length; colonEnd ++) {
            char ch = sb.charAt(colonEnd);
            if (ch == ':') {
                colonEnd ++;
                break;
            } else if (!isOWS(ch)) {
                // Only OWS (SP / HTAB) is allowed between the header name
                // and the colon. See GHSA-wx5j-54mm-rqqq and RFC 7230 3.2.4.
                throw new IllegalArgumentException(
                        "Invalid separator, only a single space or horizontal tab allowed," +
                        " but received a '" + ch + "' (0x" + Integer.toHexString(ch) + ")");
            }
        }

        valueStart = findNonWhitespace(sb, colonEnd);
        if (valueStart == length) {
            return new String[] {
                    sb.substring(nameStart, nameEnd),
                    ""
            };
        }

        valueEnd = findEndOfString(sb);
        return new String[] {
                sb.substring(nameStart, nameEnd),
                sb.substring(valueStart, valueEnd)
        };
    }

    private static int findNonWhitespace(String sb, int offset) {
        for (int result = offset; result < sb.length(); result ++) {
            char c = sb.charAt(result);
            if (!Character.isWhitespace(c)) {
                return result;
            } else if (!isOWS(c)) {
                // Only OWS (SP / HTAB) is allowed around a header name.
                // See GHSA-wx5j-54mm-rqqq and RFC 7230 section 3.2.3.
                throw new IllegalArgumentException(
                        "Invalid separator, only a single space or horizontal tab allowed," +
                        " but received a '" + c + "' (0x" + Integer.toHexString(c) + ")");
            }
        }
        return sb.length();
    }

    private static boolean isOWS(char c) {
        return c == ' ' || c == 0x09;
    }

    private static int findWhitespace(String sb, int offset) {
        int result;
        for (result = offset; result < sb.length(); result ++) {
            if (Character.isWhitespace(sb.charAt(result))) {
                break;
            }
        }
        return result;
    }

    private static int findEndOfString(String sb) {
        int result;
        for (result = sb.length(); result > 0; result --) {
            if (!Character.isWhitespace(sb.charAt(result - 1))) {
                break;
            }
        }
        return result;
    }
}
