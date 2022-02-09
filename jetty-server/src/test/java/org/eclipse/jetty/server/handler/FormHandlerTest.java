//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class FormHandlerTest
{
    private Server server;
    private ServerConnector connector;

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(new FieldHandler());
        server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        server.stop();
    }

    @Test
    public void testNotForm() throws Exception
    {
        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            String request =
                "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
        }
    }

    @Test
    public void testSimpleForm() throws Exception
    {
        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            String request = "POST / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 11\r\n" +
                "\r\n" +
                "a=1&b=2&c=3";

            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);

            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContent(), containsString("a=[1]"));
            assertThat(response.getContent(), containsString("b=[2]"));
            assertThat(response.getContent(), containsString("c=[3]"));
        }
    }

    @Test
    public void testSlowForm() throws Exception
    {
        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            String request = "POST / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 11\r\n" +
                "\r\n";

            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();
            Thread.sleep(200);
            output.write("a=1".getBytes(StandardCharsets.UTF_8));
            output.flush();
            Thread.sleep(200);
            output.write("&b=2&c".getBytes(StandardCharsets.UTF_8));
            output.flush();
            Thread.sleep(200);
            output.write("=3".getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);

            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContent(), containsString("a=[1]"));
            assertThat(response.getContent(), containsString("b=[2]"));
            assertThat(response.getContent(), containsString("c=[3]"));
        }
    }

    public static class FieldHandler extends Handler.Abstract
    {
        @Override
        public void handle(Request request) throws Exception
        {
            String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
            if (contentType == null)
                return;
            String mimeType = MimeTypes.getContentTypeWithoutCharset(contentType);
            MimeTypes.Type type = MimeTypes.CACHE.get(mimeType);
            if (type != MimeTypes.Type.FORM_ENCODED)
                return;
            String charset = MimeTypes.getCharsetFromContentType(contentType);
            if (StringUtil.isNotBlank(charset) && !StringUtil.__UTF8.equals(charset))
                return; // TODO need to handle arbitrary charsets

            Response response = request.accept();
            Content.FieldPublisher publisher = new Content.FieldPublisher(request);
            Flow.Subscriber<Fields.Field> subscriber = new Flow.Subscriber<Fields.Field>()
            {
                Flow.Subscription _subscription;
                @Override
                public void onSubscribe(Flow.Subscription subscription)
                {
                    _subscription = subscription;
                    _subscription.request(1);
                }

                @Override
                public void onNext(Fields.Field field)
                {
                    response.write(false,
                        Callback.from(() -> _subscription.request(1), response.getCallback()::failed),
                        field + "\r\n");
                }

                @Override
                public void onError(Throwable x)
                {
                    response.getCallback().failed(x);
                }

                @Override
                public void onComplete()
                {
                    response.write(true, response.getCallback());
                }
            };
            publisher.subscribe(subscriber);
        }
    }
}
