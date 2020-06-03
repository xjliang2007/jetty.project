//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;

public class TestWebSocketUpgradeHandler extends WebSocketUpgradeHandler
{
    public TestWebSocketUpgradeHandler(WebSocketNegotiator negotiator)
    {
        super(negotiator);

        setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().println("Hello World!");
                baseRequest.setHandled(true);
            }
        });
    }
}