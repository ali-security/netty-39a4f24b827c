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

import org.junit.Assert;
import org.junit.Test;

public class DefaultHttpMessageTest {

    private static final String[] INVALID_URIS = {
            "http://localhost/\r\n",
            "/r\r\n?q=1",
            "http://localhost/\r\n?q=1",
            "/r\r\n/?q=1",
            "http://localhost/\r\n/?q=1",
            "/r\r\n",
            "http://localhost/ HTTP/1.1\r\n\r\nPOST /p HTTP/1.1\r\n\r\n",
            "/r HTTP/1.1\r\n\r\nPOST /p HTTP/1.1\r\n\r\n",
            "/ path",
            "/path ",
            " /path",
            "http://localhost/ ",
            " http://localhost/",
            "http://local host/"
    };

    private static final String[] VALID_URIS = {
            "/",
            "/path",
            "/path?query=value",
            "http://localhost/",
            "http://localhost/path",
            "*"
    };

    private static final String[] INVALID_METHODS = {
            "GET ",
            " GET",
            "G ET",
            " GET ",
            "GET\r",
            "GET\n",
            "GET\r\n",
            "GE\rT",
            "GE\nT",
            "GE\r\nT",
            "\rGET",
            "\nGET",
            "\r\nGET",
            " \r\nGET",
            "\r \nGET",
            "\r\n GET",
            "\r\nGET ",
            "\nGET ",
            "\rGET ",
            "\r GET",
            " \rGET",
            "\n GET",
            " \nGET",
            "GET \n",
            "GET \r",
            " GET\r",
            " GET\n",
            "GE\nT ",
            "GE\rT ",
            " GE\rT",
            " GE\nT"
    };

    private static final String[] VALID_METHODS = {
            "GET",
            "POST",
            "PUT",
            "HEAD",
            "DELETE",
            "OPTIONS",
            "CONNECT",
            "TRACE",
            "PATCH",
            "QUERY"
    };

    private static final String[] INVALID_PROTOCOLS = {
            "HTTP ",
            " HTTP",
            "H TTP",
            " HTTP ",
            "HTTP\r",
            "HTTP\n",
            "HTTP\r\n",
            "HTT\rP",
            "HTT\nP",
            "HTT\r\nP",
            "\rHTTP",
            "\nHTTP",
            "\r\nHTTP",
            " \r\nHTTP",
            "\r \nHTTP",
            "\r\n HTTP",
            "\r\nHTTP ",
            "\nHTTP ",
            "\rHTTP ",
            "\r HTTP",
            " \rHTTP",
            "\n HTTP",
            " \nHTTP",
            "HTTP \n",
            "HTTP \r",
            " HTTP\r",
            " HTTP\n",
            "HTT\nTP",
            "HTT\rTP",
            " HTT\rP",
            " HTT\nP"
    };

    private static HttpMethod methodWithName(final String name) {
        return new HttpMethod("GET") {
            @Override
            public String toString() {
                return name;
            }
        };
    }

    private static boolean constructorRejects(HttpMethod method, String uri) {
        try {
            new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, uri);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static boolean setUriRejects(HttpRequest request, String uri) {
        try {
            request.setUri(uri);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static boolean setMethodRejects(HttpRequest request, HttpMethod method) {
        try {
            request.setMethod(method);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    @Test
    public void testConstructorRejectsIllegalUri() {
        for (String uri : INVALID_URIS) {
            Assert.assertTrue(uri, constructorRejects(HttpMethod.GET, uri));
        }
    }

    @Test
    public void testSetUriRejectsIllegalUri() {
        for (String uri : INVALID_URIS) {
            HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
            Assert.assertTrue(uri, setUriRejects(request, uri));
            Assert.assertEquals("/", request.getUri());
        }
    }

    @Test
    public void testRequestRejectsControlCharactersSpaceAndDelInUri() {
        for (char c = 0; c <= 0x20; c ++) {
            String uri = "/a" + c + "HTTP/1.0";
            String hex = "0x" + Integer.toHexString(c);
            Assert.assertTrue(hex, constructorRejects(HttpMethod.GET, uri));
            Assert.assertTrue(hex, setUriRejects(
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"), uri));
        }
        Assert.assertTrue(constructorRejects(HttpMethod.GET, "/a\u007FHTTP/1.0"));
        Assert.assertTrue(setUriRejects(
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"), "/a\u007FHTTP/1.0"));
    }

    @Test
    public void testRequestAcceptsValidUri() {
        for (String uri : VALID_URIS) {
            Assert.assertEquals(uri, new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri).getUri());
            HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
            request.setUri(uri);
            Assert.assertEquals(uri, request.getUri());
        }
    }

    @Test
    public void testConstructorRejectsIllegalMethod() {
        for (String method : INVALID_METHODS) {
            Assert.assertTrue(method, constructorRejects(methodWithName(method), "/"));
        }
    }

    @Test
    public void testSetMethodRejectsIllegalMethod() {
        for (String method : INVALID_METHODS) {
            HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
            Assert.assertTrue(method, setMethodRejects(request, methodWithName(method)));
            Assert.assertSame(HttpMethod.GET, request.getMethod());
        }
    }

    @Test
    public void testRequestAcceptsValidMethod() {
        for (String method : VALID_METHODS) {
            new DefaultHttpRequest(HttpVersion.HTTP_1_0, methodWithName(method), "/");
            new DefaultHttpRequest(HttpVersion.HTTP_1_0, new HttpMethod(method), "/");
            HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
            request.setMethod(methodWithName(method));
            request.setMethod(new HttpMethod(method));
            Assert.assertEquals(method, request.getMethod().toString());
        }
    }

    @Test
    public void testHttpVersionRejectsIllegalTokens() {
        for (String protocol : INVALID_PROTOCOLS) {
            HttpVersion version;
            try {
                version = new HttpVersion(protocol, 1, 0, true);
            } catch (IllegalArgumentException e) {
                continue;
            }
            Assert.assertTrue(protocol, HttpCodecUtil.isEncodingSafeStartLineToken(version.getText()));
        }
    }

    @Test
    public void testHeaderRemoval() {
        HttpMessage m = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

        // Insert sample keys.
        for (int i = 0; i < 1000; i ++) {
            m.setHeader(String.valueOf(i), "");
        }

        // Remove in reversed order.
        for (int i = 999; i >= 0; i --) {
            m.removeHeader(String.valueOf(i));
        }

        // Check if random access returns nothing.
        for (int i = 0; i < 1000; i ++) {
            Assert.assertNull(m.getHeader(String.valueOf(i)));
        }

        // Check if sequential access returns nothing.
        Assert.assertTrue(m.getHeaders().isEmpty());
    }

    // GHSA-wx5j-54mm-rqqq: header names must not contain the control characters 0x1c-0x1f.
    @Test
    public void testHeaderNameWithControlCharRejected() {
        HttpMessage m = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

        for (char c = 0x1c; c <= 0x1f; c ++) {
            String name = "Transfer" + c + "Encoding";
            try {
                m.addHeader(name, "chunked");
                Assert.fail("Expected IllegalArgumentException for 0x" + Integer.toHexString(c));
            } catch (IllegalArgumentException expected) {
                // expected
            }
            try {
                m.setHeader(name, "chunked");
                Assert.fail("Expected IllegalArgumentException for 0x" + Integer.toHexString(c));
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
        Assert.assertTrue(m.getHeaders().isEmpty());
    }
}
