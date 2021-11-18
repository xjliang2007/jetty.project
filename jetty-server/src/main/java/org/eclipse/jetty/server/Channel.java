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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.SerializedInvoker;
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

    private final AutoLock _lock = new AutoLock();
    private final Server _server;
    private final ConnectionMetaData _connectionMetaData;
    private final HttpConfiguration _configuration;
    private final SerializedInvoker _serializedInvocation;
    private Stream _stream;
    private int _requests;
    private Content.Error _error;
    private BiConsumer<Request, Response> _onCommit = UNCOMMITTED;
    private Consumer<Throwable> _onConnectionComplete;
    private ChannelRequest _request;
    private ChannelResponse _response;

    public Channel(Server server, ConnectionMetaData connectionMetaData, HttpConfiguration configuration)
    {
        _server = server;
        _connectionMetaData = connectionMetaData;
        _configuration = configuration;
        _serializedInvocation = new SerializedInvoker(_server.getThreadPool());
    }

    public void bind(Stream stream)
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (_stream != null)
                throw new IllegalStateException("Stream pending");
            _stream = stream;
            _onCommit = UNCOMMITTED;
        }
    }

    public Stream getStream()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _request.getStream();
        }
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

    public Runnable onRequest(MetaData.Request request)
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onRequest {} {}", request, this);

            _requests++;
            _request = new ChannelRequest(request);
            _response = new ChannelResponse();

            if (!_request.getPath().startsWith("/") && !HttpMethod.OPTIONS.is(request.getMethod()) && !HttpMethod.CONNECT.is(request.getMethod()))
                throw new BadMessageException();

            HttpURI uri = request.getURI();
            if (uri.hasViolations())
            {
                String badMessage = UriCompliance.checkUriCompliance(_configuration.getUriCompliance(), uri);
                if (badMessage != null)
                    throw new BadMessageException(badMessage);
            }

            // TODO invocation type of this Runnable
            //      currently the only way to do this would be to insist that Handler.handle never blocked and
            //      returned a Runnable instead of a boolean.
            //      If it is blocking, then it can't be mutually excluded from other callbacks
            return this::handle;
        }
    }

    protected Request getRequest()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _request;
        }
    }

    protected Response getResponse()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _response;
        }
    }

    public Runnable onContentAvailable()
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (_request == null)
                return null;
            Runnable onContent = _request._onContent;
            _request._onContent = null;
            return _serializedInvocation.invoke(onContent);
        }
    }

    public Invocable.InvocationType getOnContentAvailableInvocationType()
    {
        // TODO Can this actually be done, as we may need to invoke other Runnables after onContent?
        try (AutoLock ignored = _lock.lock())
        {
            return Invocable.getInvocationType(_request == null ? null : _request._onContent);
        }
    }

    public boolean onIdleTimeout(long now, long timeoutNanos)
    {
        // TODO check time against last activity and return true only if we really are idle.
        //      If we return true, then onError will be called with a real exception... or is that too late????
        return true;
    }

    public Runnable onError(Throwable x)
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onError {} {}", this, x);

            if (_request == null || _stream == null)
                return null;

            ChannelRequest request = _request;

            // Remember the error
            if (_error == null)
                _error = new Content.Error(x);
            else if (_error.getCause() != x)
                _error.getCause().addSuppressed(x);

            // TODO if we are currently demanding, do we include a call to onDataAvailable in the return?
            Runnable onDataAvailable = null;

            // TODO if a write is in progress, do we break the linkage and fail the callback
            Runnable writeFailure = null;

            // TODO should we arrange for any subsequent writes to fail with this error or rely on the request.failed below?
            Consumer<Throwable> onError = request._onError;
            Runnable invokeOnError = onError == null ? null : () -> onError.accept(x);

            // TODO we we always ultimately fail the request?
            return _serializedInvocation.invoke(onDataAvailable, writeFailure, invokeOnError, () -> request.failed(x));
        }
    }

    public Runnable onConnectionClose(Throwable failed)
    {
        Stream stream;
        Consumer<Throwable> onConnectionClose;
        try (AutoLock ignored = _lock.lock())
        {
            stream = _stream;
            _stream = null;
            onConnectionClose = _onConnectionComplete;
            _onConnectionComplete = null;
        }

        if (onConnectionClose == null)
            return stream == null ? null : _serializedInvocation.invoke(() -> stream.failed(failed));

        if (stream == null)
            return _serializedInvocation.invoke(() -> onConnectionClose.accept(failed));

        return _serializedInvocation.invoke(() -> stream.failed(failed), () -> onConnectionClose.accept(failed));
    }

    public void whenStreamEvent(Function<Stream, Stream.Wrapper> onStreamEvent)
    {
        while (true)
        {
            Stream stream;
            try (AutoLock ignored = _lock.lock())
            {
                stream = _stream;
            }
            if (_stream == null)
                throw new IllegalStateException("No active stream");
            Stream.Wrapper combined = onStreamEvent.apply(stream);
            if (combined == null || combined.getWrapped() != stream)
                throw new IllegalArgumentException("Cannot remove stream");
            try (AutoLock ignored = _lock.lock())
            {
                if (_stream != stream)
                    continue;
                _stream = combined;
                break;
            }
        }
    }

    public void whenConnectionComplete(Consumer<Throwable> onComplete)
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (_onConnectionComplete == null)
                _onConnectionComplete = onComplete;
            else
            {
                Consumer<Throwable> previous = _onConnectionComplete;
                _onConnectionComplete = (failed) ->
                {
                    notifyConnectionClose(previous, failed);
                    notifyConnectionClose(onComplete, failed);
                };
            }
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
        if (!_server.handle(_request, _response))
            throw new IllegalStateException();
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
        final MetaData.Request _metaData;
        final String _id = Integer.toString(_requests);
        Consumer<Throwable> _onError;
        Runnable _onContent;

        private Request _wrapper = this;

        ChannelRequest(MetaData.Request metaData)
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
            try (AutoLock ignored = _lock.lock())
            {
                return _stream == null || _stream.isComplete();
            }
        }

        Stream getStream()
        {
            try (AutoLock ignored = _lock.lock())
            {
                if (_stream == null)
                    throw new IllegalStateException();
                return _stream;
            }
        }
        
        @Override
        public String getId()
        {
            return _id;
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
            return _metaData.getURI().getDecodedPath();
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
            try (AutoLock ignored = _lock.lock())
            {
                if (_error != null)
                    return _error;
            }
            return getStream().readContent();
        }

        @Override
        public void demandContent(Runnable onContentAvailable)
        {
            boolean error;
            try (AutoLock ignored = _lock.lock())
            {
                if (_onContent != null && _onContent != onContentAvailable)
                    throw new IllegalArgumentException();
                _onContent = onContentAvailable;
                error = _error != null;
            }

            if (error)
                _serializedInvocation.execute(Channel.this.onContentAvailable());
            else
                getStream().demandContent();
        }

        @Override
        public void ifError(Consumer<Throwable> onError)
        {
            try (AutoLock ignored = _lock.lock())
            {
                if (_error != null)
                {
                    _serializedInvocation.execute(() -> onError.accept(_error.getCause()));
                    return;
                }

                if (_onError == null)
                    _onError = onError;
                else
                {
                    Consumer<Throwable> previous = _onError;
                    _onError = throwable ->
                    {
                        try
                        {
                            previous.accept(throwable);
                        }
                        finally
                        {
                            onError.accept(throwable);
                        }
                    };
                }
            }
        }

        @Override
        public void whenComplete(Callback onComplete)
        {
            whenStreamEvent(s -> new Stream.Wrapper(s)
            {
                @Override
                public void succeeded()
                {
                    try
                    {
                        onComplete.succeeded();
                    }
                    finally
                    {
                        super.succeeded();
                    }
                }

                @Override
                public void failed(Throwable x)
                {
                    try
                    {
                        onComplete.failed(x);
                    }
                    finally
                    {
                        super.failed(x);
                    }
                }
            });
        }

        @Override
        public void succeeded()
        {
            Stream stream;
            try (AutoLock ignored = _lock.lock())
            {
                stream = _stream;
                _stream = null;
            }

            if (stream == null)
                return;

            // Commit and complete the response
            stream.send(_response.commitResponse(true), true, Callback.from(() ->
            {
                // ensure the request is consumed
                Throwable unconsumed = stream.consumeAll();
                if (LOG.isDebugEnabled())
                    LOG.debug("consumeAll {} ", this, unconsumed);
                if (unconsumed != null && getConnectionMetaData().isPersistent())
                    stream.failed(unconsumed);
                else
                    stream.succeeded();
            }, stream::failed));
        }

        @Override
        public void failed(Throwable x)
        {
            // This is equivalent to the previous HttpTransport.abort(Throwable), so we don't need to do much clean up
            // as channel will be shutdown and thrown away.
            Stream stream;
            try (AutoLock ignored = _lock.lock())
            {
                stream = _stream;
                _stream = null;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("failed {} {}", stream, x);
            if (stream != null)
                stream.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return getStream().getInvocationType();
        }
    }

    private static final BiConsumer<Request, Response> UNCOMMITTED = (req, res) -> {};
    private static final BiConsumer<Request, Response> COMMITTED = (req, res) -> {};

    private class ChannelResponse implements Response
    {
        private final ChannelRequest _request = Channel.this._request;
        private final ResponseHttpFields _headers = new ResponseHttpFields();
        private ResponseHttpFields _trailers;
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
            try (AutoLock ignored = _lock.lock())
            {
                // TODO check if trailers allowed in version and transport?
                if (_trailers == null)
                    _trailers = new ResponseHttpFields();
                return _trailers;
            }
        }

        private HttpFields takeTrailers()
        {
            try (AutoLock ignored = _lock.lock())
            {
                ResponseHttpFields trailers = _trailers;
                if (trailers != null)
                    trailers.toReadOnly();
                return trailers;
            }
        }

        @Override
        public void write(boolean last, Callback callback, ByteBuffer... content)
        {
            Content.Error error;
            try (AutoLock ignored = _lock.lock())
            {
                error = _error;
            }
            if (error != null)
            {
                _serializedInvocation.execute(() -> callback.failed(error.getCause()));
                return;
            }

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

            _request.getStream().send(commit, last, callback, content);
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
            _request.getStream().push(request);
        }

        @Override
        public void whenCommitting(BiConsumer<Request, Response> onCommit)
        {
            try (AutoLock ignored = _lock.lock())
            {
                if (_onCommit == COMMITTED)
                    throw new IllegalStateException("Committed");

                if (_onCommit == UNCOMMITTED)
                    _onCommit = onCommit;
                else
                {
                    BiConsumer<Request, Response> previous = _onCommit;
                    _onCommit = (request, response) ->
                    {
                        notifyCommit(previous);
                        notifyCommit(onCommit);
                    };
                }
            }
        }

        @Override
        public boolean isCommitted()
        {
            try (AutoLock ignored = _lock.lock())
            {
                return _onCommit == COMMITTED;
            }
        }

        @Override
        public void reset()
        {
            try (AutoLock ignored = _lock.lock())
            {
                if (_onCommit == COMMITTED)
                    throw new IllegalStateException("Committed");

                _headers.clear(); // TODO re-add or don't delete default fields
                if (_trailers != null)
                    _trailers.clear();
                _status = 0;
            }
        }

        private MetaData.Response commitResponse(boolean last)
        {
            BiConsumer<Request, Response> committed;
            Supplier<HttpFields> trailers;
            try (AutoLock ignored = _lock.lock())
            {
                if (_onCommit == COMMITTED)
                    return null;

                committed = _onCommit;
                _onCommit = COMMITTED;

                if (_status == 0)
                    _status = 200;

                _contentLength = _headers.getLongField(HttpHeader.CONTENT_LENGTH);
                if (last && _contentLength < 0L)
                {
                    _contentLength = _written;
                    _headers.putLongField(HttpHeader.CONTENT_LENGTH, _contentLength);
                }

                if (_configuration.getSendDateHeader() && !_headers.contains(HttpHeader.DATE))
                    _headers.put(getServer().getDateField());

                _headers.toReadOnly();

                trailers = _trailers == null ? null : _response::takeTrailers;
            }

            if (committed != UNCOMMITTED)
                notifyCommit(committed);

            return new MetaData.Response(
                _request._metaData.getHttpVersion(),
                _status,
                null,
                _headers,
                -1,
                trailers);
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
