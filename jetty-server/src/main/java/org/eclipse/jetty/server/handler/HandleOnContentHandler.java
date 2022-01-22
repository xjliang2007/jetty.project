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

import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

public class HandleOnContentHandler extends Handler.Wrapper
{
    @Override
    public boolean handle(Request request, Response response) throws Exception
    {
        // If no content or content available, then don't delay dispatch
        if (request.getContentLength() <= 0 && !request.getHeaders().contains(HttpHeader.CONTENT_TYPE))
            return super.handle(request, response);

        request.content(new OnContentRunner(request, response)::onContentAvailable);
        return true;
    }

    private class OnContentRunner
    {
        private final Request _request;
        private final Response _response;
        private final AtomicBoolean _init = new AtomicBoolean();

        public OnContentRunner(Request request, Response response)
        {
            _request = request;
            _response = response;
        }

        public void onContentAvailable(Content.Producer producer)
        {
            if (_init.getAndSet(true))
            {
                try
                {
                    if (!HandleOnContentHandler.super.handle(_request, _response))
                        _request.failed(new IllegalStateException());
                }
                catch (Exception e)
                {
                    _request.failed(e);
                }
            }
        }
    }
}
