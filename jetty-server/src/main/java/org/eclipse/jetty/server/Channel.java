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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Channel represents a sequence of request cycles from the same connection. However only a single
 * request cycle may be active at once for each channel.    This is some, but not all of the
 * behaviour of the current HttpChannel class, specifically it does not include the mutual exclusion
 * of handling required by the servlet spec and currently encapsulated in HttpChannelState.
 *
 * Note how Runnables are returned to indicate that further work is needed. These
 * can be given to an ExecutionStrategy instead of calling known methods like HttpChannel.handle().
 */
public class Channel extends AttributesMap
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractConnector.class);

    public static final String UPGRADE_CONNECTION_ATTRIBUTE = Channel.class.getName() + ".UPGRADE";

    private final Server _server;
    private final ConnectionMetaData _connectionMetaData;
    private final AtomicInteger _requests = new AtomicInteger();
    private final AtomicReference<Consumer<Throwable>> _onConnectionClose = new AtomicReference<>();
    private final AtomicReference<Stream> _stream = new AtomicReference<>();
    private ChannelRequest _request;
    private ChannelResponse _response;

    public Channel(Server server, ConnectionMetaData connectionMetaData)
    {
        _server = server;
        _connectionMetaData = connectionMetaData;
    }

    public Server getServer()
    {
        return _server;
    }

    public ConnectionMetaData getMetaConnection()
    {
        return _connectionMetaData;
    }

    public Connection getConnection()
    {
        return _connectionMetaData.getConnection();
    }

    public Connector getConnector()
    {
        return _connectionMetaData.getConnector();
    }

    public Stream getStream()
    {
        return _request.stream();
    }

    public Runnable onRequest(MetaData.Request request, Stream stream)
    {
        if (!_stream.compareAndSet(null, stream))
            throw new IllegalStateException("Stream pending");

        _requests.incrementAndGet();

        // TODO wrapping behaviour makes recycling requests kind of pointless, as much of the things that benefit
        //      from reuse are in the wrappers.   So for example, now in ServletContextHandler, we make the effort
        //      to recycle the ServletRequestState object and add that to the new request. Likewise, at this level
        //      we need to determine if some expensive resources are best moved to the channel and referenced by the
        //      request - eg perhaps AttributeMap?     But then should that reference be volatile and breakable?
        _request = new ChannelRequest(request);
        _response = new ChannelResponse();

        // Mock request log
        RequestLog requestLog = _server.getRequestLog();
        if (requestLog != null)
        {
            whenStreamEvent(s ->
                new Stream.Wrapper(s)
                {
                    MetaData.Response _responseMeta;

                    @Override
                    public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
                    {
                        // TODO wrap callback to work out data rate
                        if (response != null)
                            _responseMeta = response;
                        super.send(response, last, callback, content);
                    }

                    @Override
                    public void succeeded()
                    {
                        requestLog.log(_request.getWrapper(), request, _responseMeta);
                        super.succeeded();
                    }
                });
        }

        return this::handle;
    }

    protected Request getRequest()
    {
        return _request;
    }

    protected Response getResponse()
    {
        return _response;
    }

    public Runnable onContentAvailable()
    {
        return _request._onContent.getAndSet(null);
    }

    public Invocable.InvocationType getOnContentAvailableInvocationType()
    {
        Runnable onContentAvailable = _request._onContent.get();
        return onContentAvailable == null
            ? Invocable.InvocationType.BLOCKING
            : Invocable.getInvocationType(onContentAvailable);
    }

    public Runnable onConnectionClose(Throwable failed)
    {
        Stream stream = _stream.getAndSet(null);
        if (stream != null)
            stream.failed(failed);
        notifyConnectionClose(_onConnectionClose.getAndSet(null), failed);
        return null;
    }

    public void whenStreamComplete(Consumer<Throwable> onComplete)
    {
        whenStreamEvent(s -> new Stream.Wrapper(s)
        {
            @Override
            public void succeeded()
            {
                super.succeeded();
                onComplete.accept(null);
            }

            @Override
            public void failed(Throwable x)
            {
                super.failed(x);
                onComplete.accept(x);
            }
        });
    }

    public void whenStreamEvent(UnaryOperator<Stream> onStreamEvent)
    {
        _stream.getAndUpdate(s ->
        {
            if (s == null)
                throw new IllegalStateException("No active stream");
            s = onStreamEvent.apply(s);
            if (s == null)
                throw new IllegalArgumentException("Cannot remove stream");
            return s;
        });
    }

    public void whenConnectionComplete(Consumer<Throwable> onComplete)
    {
        if (!_onConnectionClose.compareAndSet(null, onComplete))
        {
            _onConnectionClose.getAndUpdate(l -> (failed) ->
            {
                notifyConnectionClose(l, failed);
                notifyConnectionClose(onComplete, failed);
            });
        }
    }

    /** Format the address or host returned from Request methods
     * @param addr The address or host
     * @return Default implementation returns {@link HostPort#normalizeHost(String)}
     */
    protected String formatAddrOrHost(String addr)
    {
        return HostPort.normalizeHost(addr);
    }

    private void handle()
    {
        try
        {
            if (!_server.handle(_request, _response))
            {
                if (_response.isCommitted())
                {
                    _request.failed(new IllegalStateException("Not Completed"));
                }
                else
                {
                    _response.reset();
                    _response.setStatus(404);
                    _request.succeeded();
                }
            }
        }
        catch (Throwable t)
        {
            handleException(t);
        }
    }

    protected void handleException(Throwable t)
    {
        if (_response.isCommitted())
        {
            _request.failed(t == null ? new IllegalStateException() : t);
        }
        else
        {
            // TODO error page?
            _response.reset();
            _response.setStatus(500);
            _request.succeeded();
        }
    }

    private void notifyConnectionClose(Consumer<Throwable> onConnectionComplete, Throwable failed)
    {
        if (onConnectionComplete != null)
        {
            try
            {
                onConnectionComplete.accept(failed);
            }
            catch (Throwable t)
            {
                t.printStackTrace();
            }
        }
    }

    private class ChannelRequest extends AttributesMap implements Request
    {
        private final MetaData.Request _metaData;
        private final AtomicReference<Runnable> _onContent = new AtomicReference<>();
        private final AtomicReference<Object> _onTrailers = new AtomicReference<>();

        private Request _wrapper = this;

        private ChannelRequest(MetaData.Request metaData)
        {
            _metaData = metaData;
        }

        @Override
        public void setWrapper(Request wrapper)
        {
            if (_wrapper != null && wrapper.getWrapped() != _wrapper)
                throw new IllegalStateException("B B B Bad rapping!");
            _wrapper = wrapper;
        }

        @Override
        public Request getWrapper()
        {
            return _wrapper;
        }

        @Override
        public boolean isComplete()
        {
            Stream s = _stream.get();
            return s == null || s.isComplete();
        }

        Stream stream()
        {
            Stream s = _stream.get();
            if (s == null)
                throw new IllegalStateException();
            return s;
        }
        
        @Override
        public String getId()
        {
            return Integer.toString(_requests.get());
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            return _connectionMetaData;
        }

        @Override
        public Channel getChannel()
        {
            return Channel.this;
        }

        @Override
        public String getMethod()
        {
            return _metaData.getMethod();
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _metaData.getURI();
        }

        @Override
        public String getPath()
        {
            return _metaData.getURI().getPath();
        }

        @Override
        public HttpFields getHeaders()
        {
            return _metaData.getFields();
        }

        @Override
        public long getContentLength()
        {
            return _metaData.getContentLength();
        }

        @Override
        public Content readContent()
        {
            return stream().readContent();
        }

        @Override
        public void demandContent(Runnable onContentAvailable)
        {
            Runnable task = _onContent.getAndSet(onContentAvailable);
            if (task != null && task != onContentAvailable)
                throw new IllegalStateException();
            stream().demandContent();
        }

        @Override
        public void succeeded()
        {
            Stream s = _stream.getAndSet(null);
            if (s == null)
                return;

            // Cannot handle trailers after succeeded
            _onTrailers.set(null);

            // Commit and complete the response
            s.send(_response.commitResponse(true), true, Callback.from(() ->
            {
                // ensure the request is consumed
                Throwable unconsumed = s.consumeAll();
                if (LOG.isDebugEnabled())
                    LOG.debug("consumeAll {} ", this, unconsumed);
                if (unconsumed != null && getConnectionMetaData().isPersistent())
                    s.failed(unconsumed);
                else
                    s.succeeded();
            }, s::failed));
        }

        @Override
        public void failed(Throwable x)
        {
            // TODO should we send a 500 if we are not committed?
            // This is equivalent to the previous HttpTransport.abort(Throwable), so we don't need to do much clean up
            // as channel will be shutdown and thrown away.
            Stream s = _stream.getAndSet(null);
            if (s != null)
                s.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return stream().getInvocationType();
        }
    }

    private static final BiConsumer<Request, Response> UNCOMMITTED = (req, res) -> {};
    private static final BiConsumer<Request, Response> COMMITTED = (req, res) -> {};

    private class ChannelResponse implements Response
    {
        // TODO are all these atomics worth while?
        //      Multiple atomics are rarely race-free (eg _onCommit COMMITTED, but _headers not yet Immutable)
        //      Would we be better to synchronise??
        //      Or maybe just not be thread safe?
        private final AtomicReference<BiConsumer<Request, Response>> _onCommit = new AtomicReference<>(UNCOMMITTED);
        private final HttpFields.Mutable _headers = HttpFields.build(); // TODO init
        private final AtomicReference<HttpFields> _trailers = new AtomicReference<>();
        private final ChannelRequest _request = Channel.this._request;
        private long _written;
        private long _contentLength = -1L;
        private Response _wrapper;
        private int _status;

        @Override
        public Request getRequest()
        {
            return _request.getWrapper();
        }

        @Override
        public Response getWrapper()
        {
            return _wrapper;
        }

        @Override
        public void setWrapper(Response wrapper)
        {
            if (_wrapper != null && wrapper.getWrapped() != _wrapper)
                throw new IllegalStateException("Bbb b bad rapping!");
            _wrapper = wrapper;
        }

        @Override
        public int getStatus()
        {
            return _status;
        }

        @Override
        public void setStatus(int code)
        {
            if (!isCommitted())
                _status = code;
        }

        @Override
        public HttpFields.Mutable getHeaders()
        {
            return _headers;
        }

        @Override
        public HttpFields.Mutable getTrailers()
        {
            HttpFields trailers = _trailers.updateAndGet(t ->
            {
                // TODO check if trailers allowed in version and transport?
                if (t == null)
                    return HttpFields.build();
                return t;
            });

            if (trailers instanceof HttpFields.Mutable)
                return (HttpFields.Mutable)trailers;
            return null;
        }

        private HttpFields takeTrailers()
        {
            HttpFields trailers = _trailers.get();
            if (trailers != null)
                trailers.toReadOnly();
            return trailers;
        }

        @Override
        public void write(boolean last, Callback callback, ByteBuffer... content)
        {
            for (ByteBuffer b : content)
                _written += b.remaining();
            if (_contentLength >= 0)
            {
                if (_contentLength < _written)
                {
                    fail(callback, "content-length %d < %d", _contentLength, _written);
                    return;
                }
                if (last && _contentLength > _written)
                {
                    fail(callback, "content-length %d > %d", _contentLength, _written);
                    return;
                }
            }

            MetaData.Response commit = commitResponse(last);
            if (commit != null && last && _contentLength != _written)
            {
                fail(callback, "content-length %d != %d", _contentLength, _written);
                return;
            }

            _request.stream().send(commit, last, callback, content);
        }

        private void fail(Callback callback, String reason, Object... args)
        {
            IOException failure = new IOException(String.format(reason, args));
            if (callback != null)
                callback.failed(failure);
            if (!getRequest().isComplete())
                getRequest().failed(failure);
        }

        @Override
        public void push(MetaData.Request request)
        {
            _request.stream().push(request);
        }

        @Override
        public void whenCommitting(BiConsumer<Request, Response> onCommit)
        {
            _onCommit.getAndUpdate(l ->
            {
                if (l == COMMITTED)
                    throw new IllegalStateException("Committed");

                if (l == UNCOMMITTED)
                    return onCommit;

                return (request, response) ->
                {
                    notifyCommit(l);
                    notifyCommit(onCommit);
                };
            });
        }

        @Override
        public boolean isCommitted()
        {
            return _onCommit.get() == COMMITTED;
        }

        @Override
        public void reset()
        {
            if (isCommitted())
                throw new IllegalStateException("Committed");
            _headers.clear(); // TODO re-add or don't delete default fields
            HttpFields trailers = _trailers.get();
            if (trailers instanceof HttpFields.Mutable)
                ((HttpFields.Mutable)trailers).clear();
            _status = 0;
        }

        private MetaData.Response commitResponse(boolean last)
        {
            BiConsumer<Request, Response> committed = _onCommit.getAndSet(COMMITTED);
            if (committed == COMMITTED)
                return null;

            if (committed != UNCOMMITTED)
                notifyCommit(committed);

            _contentLength = _headers.getLongField(HttpHeader.CONTENT_LENGTH);
            if (last && _contentLength < 0L)
            {
                _contentLength = _written;
                _headers.putLongField(HttpHeader.CONTENT_LENGTH, _contentLength);
            }

            if (_status == 0)
                _status = 200;

            return new MetaData.Response(
                _request._metaData.getHttpVersion(),
                _status,
                null,
                _headers.toReadOnly(),
                -1,
                _response::takeTrailers);
        }

        private void notifyCommit(BiConsumer<Request, Response> onCommit)
        {
            if (onCommit != null)
            {
                try
                {
                    onCommit.accept(_request, _response);
                }
                catch (Throwable t)
                {
                    t.printStackTrace();
                }
            }
        }
    }
}
