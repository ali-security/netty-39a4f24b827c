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
package org.jboss.netty.handler.codec.http;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import static org.jboss.netty.handler.codec.http.HttpCodecUtil.*;
import static org.junit.Assert.*;

public class HttpCodecUtilTest {

    @Test
    public void isEncodingSafeStartLineTokenRejectsInvalidTokens() {
        String[] tokens = {
                "http://localhost/\r\n",
                "/r\r\n?q=1",
                "http://localhost/\r\n?q=1",
                "/r\r\n/?q=1",
                "http://localhost/\r\n/?q=1",
                "/r\r\n",
                "http://localhost/ HTTP/1.1\r\n\r\nPOST /p HTTP/1.1\r\n\r\n",
                "/r HTTP/1.1\r\n\r\nPOST /p HTTP/1.1\r\n\r\n",
                "GET ",
                " GET",
                "HTTP/ 1.1",
                "HTTP/\r0.9",
                "HTTP/\n1.1",
                "/a\tHTTP/1.0",
                "/admin\u0000/public",
                "/a\u000Bb",
                "/a\fb",
                "/a\u001Bb",
                "/a\u007Fb"
        };
        for (String token : tokens) {
            assertFalse(token, isEncodingSafeStartLineToken(token));
        }
    }

    @Test
    public void isEncodingSafeStartLineTokenAcceptsValidTokens() {
        assertTrue(isEncodingSafeStartLineToken("/path/to/resource?q=1&r=2"));
        assertTrue(isEncodingSafeStartLineToken("http://localhost/\u00e9"));
        assertTrue(isEncodingSafeStartLineToken("*"));
        assertTrue(isEncodingSafeStartLineToken(""));
    }

    @Test
    public void isEncodingSafeStartLineTokenRejectsAllControlCharactersSpaceAndDel() {
        for (char c = 0; c <= 0x20; c ++) {
            assertFalse("0x" + Integer.toHexString(c), isEncodingSafeStartLineToken("/a" + c + "b"));
        }
        assertFalse(isEncodingSafeStartLineToken("/a\u007Fb"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void normalizeAndGetContentLengthEmpty() {
        normalizeAndGetContentLength(Collections.singletonList(""), false, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void normalizeAndGetContentLengthNotANumber() {
        normalizeAndGetContentLength(Collections.singletonList("foo"), false, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void normalizeAndGetContentLengthNegative() {
        normalizeAndGetContentLength(Collections.singletonList("-1"), false, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void normalizeAndGetContentLengthPositiveSign() {
        normalizeAndGetContentLength(Collections.singletonList("+1"), false, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void normalizeAndGetContentLengthMultipleHttp10() {
        normalizeAndGetContentLength(Arrays.asList("1", "1"), true, false);
    }

    @Test
    public void normalizeAndGetContentLengthNoFields() {
        assertEquals(-1, normalizeAndGetContentLength(Collections.<String>emptyList(), false, false));
    }

    @Test
    public void normalizeAndGetContentLengthValid() {
        assertEquals(42, normalizeAndGetContentLength(Collections.singletonList("42"), false, false));
    }

    @Test
    public void normalizeAndGetContentLengthAllowDuplicates() {
        assertEquals(42, normalizeAndGetContentLength(Arrays.asList("42, 42", "42"), true, true));
    }
}
