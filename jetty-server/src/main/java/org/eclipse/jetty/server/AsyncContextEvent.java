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

package org.eclipse.jetty.server;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.thread.Scheduler;

public class AsyncContextEvent extends AsyncEvent implements Runnable
{
    private final Context _context;
    private final AsyncContextState _asyncContext;
    private final HttpURI _baseURI;
    private volatile HttpChannelState _state;
    private ServletContext _dispatchContext;
    private String _dispatchPath;
    private volatile Scheduler.Task _timeoutTask;
    private Throwable _throwable;

    public AsyncContextEvent(Context context, AsyncContextState asyncContext, HttpChannelState state, Request baseRequest, ServletRequest request, ServletResponse response)
    {
        this (context, asyncContext, state, baseRequest, request, response, null);
    }

    public AsyncContextEvent(Context context, AsyncContextState asyncContext, HttpChannelState state, Request baseRequest, ServletRequest request, ServletResponse response, HttpURI baseURI)
    {
        super(null, request, response, null);
        _context = context;
        _asyncContext = asyncContext;
        _state = state;
        _baseURI = baseURI;

        // If we haven't been async dispatched before
        if (baseRequest.getAttribute(AsyncContext.ASYNC_REQUEST_URI) == null)
        {
            // We are setting these attributes during startAsync, when the spec implies that
            // they are only available after a call to AsyncContext.dispatch(...);

            // have we been forwarded before?
            String uri = (String)baseRequest.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
            if (uri != null)
            {
                baseRequest.setAttribute(AsyncContext.ASYNC_REQUEST_URI, uri);
                baseRequest.setAttribute(AsyncContext.ASYNC_CONTEXT_PATH, baseRequest.getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH));
                baseRequest.setAttribute(AsyncContext.ASYNC_SERVLET_PATH, baseRequest.getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH));
                baseRequest.setAttribute(AsyncContext.ASYNC_PATH_INFO, baseRequest.getAttribute(RequestDispatcher.FORWARD_PATH_INFO));
                baseRequest.setAttribute(AsyncContext.ASYNC_QUERY_STRING, baseRequest.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING));
                baseRequest.setAttribute(AsyncContext.ASYNC_MAPPING, baseRequest.getAttribute(RequestDispatcher.FORWARD_MAPPING));
            }
            else
            {
                baseRequest.setAttribute(AsyncContext.ASYNC_REQUEST_URI, baseRequest.getRequestURI());
                baseRequest.setAttribute(AsyncContext.ASYNC_CONTEXT_PATH, baseRequest.getContextPath());
                baseRequest.setAttribute(AsyncContext.ASYNC_SERVLET_PATH, baseRequest.getServletPath());
                baseRequest.setAttribute(AsyncContext.ASYNC_PATH_INFO, baseRequest.getPathInfo());
                baseRequest.setAttribute(AsyncContext.ASYNC_QUERY_STRING, baseRequest.getQueryString());
                baseRequest.setAttribute(AsyncContext.ASYNC_MAPPING, baseRequest.getHttpServletMapping());
            }
        }
    }

    public HttpURI getBaseURI()
    {
        return _baseURI;
    }

    public ServletContext getSuspendedContext()
    {
        return _context;
    }

    public Context getContext()
    {
        return _context;
    }

    public ServletContext getDispatchContext()
    {
        return _dispatchContext;
    }

    public ServletContext getServletContext()
    {
        return _dispatchContext == null ? _context : _dispatchContext;
    }

    public void setTimeoutTask(Scheduler.Task task)
    {
        _timeoutTask = task;
    }

    public boolean hasTimeoutTask()
    {
        return _timeoutTask != null;
    }

    public void cancelTimeoutTask()
    {
        Scheduler.Task task = _timeoutTask;
        _timeoutTask = null;
        if (task != null)
            task.cancel();
    }

    @Override
    public AsyncContext getAsyncContext()
    {
        return _asyncContext;
    }

    @Override
    public Throwable getThrowable()
    {
        return _throwable;
    }

    public void setDispatchContext(ServletContext context)
    {
        _dispatchContext = context;
    }

    /**
     * @return The path in the context (encoded with possible query string)
     */
    public String getDispatchPath()
    {
        return _dispatchPath;
    }

    /**
     * @param path encoded URI
     */
    public void setDispatchPath(String path)
    {
        _dispatchPath = path;
    }

    public void completed()
    {
        _timeoutTask = null;
        _asyncContext.reset();
    }

    public HttpChannelState getHttpChannelState()
    {
        return _state;
    }

    @Override
    public void run()
    {
        Scheduler.Task task = _timeoutTask;
        _timeoutTask = null;
        if (task != null)
            _state.timeout();
    }

    public void addThrowable(Throwable e)
    {
        if (_throwable == null)
            _throwable = e;
        else if (e != _throwable)
            _throwable.addSuppressed(e);
    }
}
