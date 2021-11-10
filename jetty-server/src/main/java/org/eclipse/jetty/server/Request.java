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

package org.eclipse.jetty.server;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;

public interface Request extends Attributes, Callback
{
    String getId();

    Channel getChannel();

    boolean isComplete();

    ConnectionMetaData getConnectionMetaData();

    String getMethod();

    HttpURI getHttpURI();

    String getPath();

    HttpFields getHeaders();

    default boolean isSecure()
    {
        return getConnectionMetaData().isSecure();
    }

    long getContentLength();

    Content readContent();

    void demandContent(Runnable onContentAvailable);

    void onTrailers(Consumer<HttpFields> onTrailers);

    default Request getWrapped()
    {
        return null;
    }

    Request getWrapper();

    void setWrapper(Request request);

    default Request unwrap()
    {
        Request r = this;
        while (true)
        {
            Request w = r.getWrapper();
            if (w == null)
                return r;
            r = w;
        }
    }

    @SuppressWarnings("unchecked")
    default <R extends Request> R as(Class<R> type)
    {
        Request r = this;
        while (r != null)
        {
            if (type.isInstance(r))
                return (R)r;
            r = r.getWrapped();
        }
        return null;
    }

    default <T extends Request, R> R get(Class<T> type, Function<T, R> getter)
    {
        Request r = this;
        while (r != null)
        {
            if (type.isInstance(r))
                return getter.apply((T)r);
            r = r.getWrapped();
        }
        return null;
    }

    static String getServerName(Request request)
    {
        HttpURI uri = request.getHttpURI();
        if (uri.hasAuthority())
            return request.getChannel().formatAddrOrHost(uri.getHost());

        SocketAddress local = request.getConnectionMetaData().getLocal();
        if (local instanceof InetSocketAddress)
            return request.getChannel().formatAddrOrHost(((InetSocketAddress)local).getHostString());

        return local.toString();
    }

    static int getServerPort(Request request)
    {
        HttpURI uri = request.getHttpURI();
        if (uri.hasAuthority())
            return uri.getPort();

        SocketAddress local = request.getConnectionMetaData().getLocal();
        if (local instanceof InetSocketAddress)
            return ((InetSocketAddress)local).getPort();

        return -1;
    }

    class Wrapper extends Attributes.Wrapper implements Request
    {
        private final Request _wrapped;

        protected Wrapper(Request wrapped)
        {
            super(wrapped);
            this._wrapped = wrapped;
            wrapped.setWrapper(this);
        }

        @Override
        public boolean isComplete()
        {
            return _wrapped.isComplete();
        }

        @Override
        public void setWrapper(Request request)
        {
            _wrapped.setWrapper(request);
        }

        @Override
        public String getId()
        {
            return _wrapped.getId();
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            return _wrapped.getConnectionMetaData();
        }

        @Override
        public Channel getChannel()
        {
            return _wrapped.getChannel();
        }

        @Override
        public String getMethod()
        {
            return _wrapped.getMethod();
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _wrapped.getHttpURI();
        }

        @Override
        public String getPath()
        {
            return _wrapped.getPath();
        }

        @Override
        public HttpFields getHeaders()
        {
            return _wrapped.getHeaders();
        }

        @Override
        public long getContentLength()
        {
            return _wrapped.getContentLength();
        }

        @Override
        public Content readContent()
        {
            return _wrapped.readContent();
        }

        @Override
        public void demandContent(Runnable onContentAvailable)
        {
            _wrapped.demandContent(onContentAvailable);
        }

        @Override
        public void onTrailers(Consumer<HttpFields> onTrailers)
        {
            _wrapped.onTrailers(onTrailers);
        }

        @Override
        public Request getWrapped()
        {
            return _wrapped;
        }

        @Override
        public Request getWrapper()
        {
            return _wrapped.getWrapper();
        }

        @Override
        public void succeeded()
        {
            _wrapped.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            _wrapped.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _wrapped.getInvocationType();
        }

    }
}
