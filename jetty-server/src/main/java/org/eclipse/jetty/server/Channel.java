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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.SerializedExecutor;
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
    private final Runnable _handle = new RunHandle();
    private final Server _server;
    private final ConnectionMetaData _connectionMetaData;
    private final HttpConfiguration _configuration;
    private final SerializedExecutor _serializedExecutor;

    private Stream _stream;
    private int _requests;
    private Content.Error _error;
    private Runnable _onCommit = UNCOMMITTED;
    private Consumer<Throwable> _onConnectionComplete;
    private ChannelRequest _request;
    private ChannelResponse _response;
    private Callback _onWriteComplete;

    public Channel(Server server, ConnectionMetaData connectionMetaData, HttpConfiguration configuration)
    {
        _server = server;
        _connectionMetaData = connectionMetaData;
        _configuration = configuration;
        _serializedExecutor = new SerializedExecutor(_server.getThreadPool())
        {
            @Override
            protected void onError(Runnable task, Throwable t)
            {
                if (_error != null && _error.getCause() != null)
                {
                    if (_error.getCause() != t)
                        _error.getCause().addSuppressed(t);
                }
                super.onError(task, t);
            }
        };
    }

    public void setStream(Stream stream)
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

    /**
     * Start request handling by returning a Runnable that will call {@link Server#handle(Request, Response)}.
     * @param request The request metadata to handle.
     * @return A Runnable that will call {@link Server#handle(Request, Response)}.  This Runnable may block if
     * {@link Invocable#isNonBlockingInvocation()} returns false in the calling context.  Unlike all other Runnables
     * returned by {@link Channel} methods, this runnable is not mutually excluded or serialized against the other
     * Runnables.
     */
    public Runnable onRequest(MetaData.Request request)
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onRequest {} {}", request, this);

            if (_request != null)
                throw new IllegalStateException();

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

            // This is deliberately not serialized as to allow a handler to block.
            return _handle;
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
            Runnable onContent = _request._onContentAvailable;
            _request._onContentAvailable = null;
            return _serializedExecutor.offer(onContent);
        }
    }

    public Invocable.InvocationType getOnContentAvailableInvocationType()
    {
        // TODO Can this actually be done, as we may need to invoke other Runnables after onContent?
        try (AutoLock ignored = _lock.lock())
        {
            return Invocable.getInvocationType(_request == null ? null : _request._onContentAvailable);
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

            // If the channel doesn't have a stream, then the error is ignored
            if (_stream == null)
                return null;

            // If the channel doesn't have a request, then the error must have occurred during parsing the request header
            if (_request == null)
            {
                // Make a temp request for logging and producing 400 response.
                _requests++;
                _request = new ChannelRequest(null);
                _response = new ChannelResponse();
            }

            // Remember the error and arrange for any subsequent reads, demands or writes to fail with this error
            if (_error == null)
                _error = new Content.Error(x);
            else if (_error.getCause() != x)
            {
                _error.getCause().addSuppressed(x);
                return null;
            }

            // invoke onDataAvailable if we are currently demanding
            Runnable invokeOnContentAvailable = null;
            if (_request != null)
            {
                invokeOnContentAvailable = _request._onContentAvailable;
                _request._onContentAvailable = null;
            }

            // if a write is in progress, break the linkage and fail the callback
            Runnable invokeWriteFailure = null;
            if (_onWriteComplete != null)
            {
                Callback onWriteComplete = _onWriteComplete;
                _onWriteComplete = null;
                invokeWriteFailure = () -> onWriteComplete.failed(x);
            }

            // Invoke any onError listener(s);
            ChannelRequest request = _request;
            Consumer<Throwable> onError = request._onError;
            request._onError = null;
            Runnable invokeOnError = onError == null ? null : () -> onError.accept(x);

            // Serialize all the error actions.
            return _serializedExecutor.offer(invokeOnContentAvailable, invokeWriteFailure, invokeOnError, () -> request.failed(x));
        }
    }

    public Runnable onConnectionClose(Throwable failed)
    {
        boolean hasStream;
        Consumer<Throwable> onConnectionClose;
        try (AutoLock ignored = _lock.lock())
        {
            hasStream = _stream != null;
            onConnectionClose = _onConnectionComplete;
            _onConnectionComplete = null;
        }

        return _serializedExecutor.offer(
            hasStream ? () -> _serializedExecutor.execute(() -> onError(failed)) : null,
            onConnectionClose == null ? null : () -> onConnectionClose.accept(failed));
    }

    public void addStreamWrapper(Function<Stream, Stream.Wrapper> onStreamEvent)
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

    public void addConnectionCloseListener(Consumer<Throwable> onClose)
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (_onConnectionComplete == null)
                _onConnectionComplete = onClose;
            else
            {
                Consumer<Throwable> previous = _onConnectionComplete;
                _onConnectionComplete = (failed) ->
                {
                    notifyConnectionClose(previous, failed);
                    notifyConnectionClose(onClose, failed);
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

    private class RunHandle implements Runnable, Invocable
    {
        @Override
        public void run()
        {
            try
            {
                if (!_server.handle(_request, _response))
                    throw new IllegalStateException();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.EITHER;
        }
    }

    private class ChannelRequest extends AttributesMap implements Request
    {
        final MetaData.Request _metaData;
        final String _id = Integer.toString(_requests);
        Consumer<Throwable> _onError;
        Runnable _onContentAvailable;

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
        public void execute(Runnable task)
        {
            _server.getThreadPool().execute(task);
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
                if (_onContentAvailable != null && _onContentAvailable != onContentAvailable)
                    throw new IllegalArgumentException();
                _onContentAvailable = onContentAvailable;
                error = _error != null;
            }

            if (error)
                _serializedExecutor.execute(Channel.this.onContentAvailable());
            else
                getStream().demandContent();
        }

        @Override
        public void addErrorListener(Consumer<Throwable> onError)
        {
            try (AutoLock ignored = _lock.lock())
            {
                if (_error != null)
                {
                    _serializedExecutor.execute(() -> onError.accept(_error.getCause()));
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
        public void addCompletionListener(Callback onComplete)
        {
            addStreamWrapper(s -> new Stream.Wrapper(s)
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
            ChannelResponse response;
            try (AutoLock ignored = _lock.lock())
            {
                // We are being tough on handler implementations and expect them to not have pending operations
                // when calling succeeded or failed
                if (_onContentAvailable != null)
                    throw new IllegalStateException("onContentAvailable Pending");
                if (_onWriteComplete != null)
                    throw new IllegalStateException("write pending");

                // TODO what if
                boolean hasError = _error != null;

                stream = _stream;
                response = _response;
                _stream = null;
                _request = null;
                _response = null;
            }

            if (stream == null)
                return;

            // Commit and complete the response
            stream.send(response.commitResponse(true), true, Callback.from(() ->
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
            ChannelResponse response;
            boolean committed;
            try (AutoLock ignored = _lock.lock())
            {
                stream = _stream;
                response = _response;
                committed = _onCommit == COMMITTED;
                _stream = null;
                _request = null;
                _response = null;

                // Cancel any callbacks
                _onError = null;
                _onWriteComplete = null;
                _onContentAvailable = null;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("failed {} {}", stream, x);
            if (stream == null)
                return;

            // committed, but the stream is still able to send a response
            if (committed)
                stream.failed(x);
            else
            {
                // Try to write an error response
                response.reset();
                int status = 500;
                String reason = x.toString();
                if (x instanceof BadMessageException)
                {
                    BadMessageException bme = (BadMessageException)x;
                    status = bme.getCode();
                    reason = bme.getReason();
                }
                if (reason == null)
                    reason = HttpStatus.getMessage(status);

                response.setStatus(status);
                ByteBuffer content = BufferUtil.EMPTY_BUFFER;
                if (!HttpStatus.hasNoBody(status))
                {
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_HTML_8859_1.asString());
                    content = BufferUtil.toBuffer("<h1>Bad Message " + status + "</h1><pre>reason: " + reason + "</pre>");
                }

                stream.send(new MetaData.Response(HttpVersion.HTTP_1_1, status, response.getHeaders().asImmutable(), content.remaining()),
                    true,
                    Callback.from(
                        () -> stream.failed(x),
                        t ->
                        {
                            if (t != x)
                                x.addSuppressed(t);
                            stream.failed(x);
                        }
                    ), content);
            }
        }

        @Override
        public InvocationType getInvocationType()
        {
            return getStream().getInvocationType();
        }
    }

    private static final Runnable UNCOMMITTED = () -> {};
    private static final Runnable COMMITTED = () -> {};

    private class ChannelResponse implements Response, Callback
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
                if (_onWriteComplete != null)
                    throw new IllegalStateException("Write pending");
                _onWriteComplete = callback;
                error = _error;
            }
            if (error != null)
            {
                _serializedExecutor.execute(() -> callback.failed(error.getCause()));
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

            _request.getStream().send(commit, last, this, content);
        }

        @Override
        public void succeeded()
        {
            Callback callback;
            try (AutoLock ignored = _lock.lock())
            {
                callback = _onWriteComplete;
                _onWriteComplete = null;
            }
            if (callback != null)
                callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            Callback callback;
            try (AutoLock ignored = _lock.lock())
            {
                callback = _onWriteComplete;
                _onWriteComplete = null;
            }
            if (callback != null)
                callback.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            try (AutoLock ignored = _lock.lock())
            {
                return Invocable.getInvocationType(_onWriteComplete);
            }
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
        public void addCommitListener(Runnable onCommit)
        {
            try (AutoLock ignored = _lock.lock())
            {
                if (_onCommit == COMMITTED)
                    throw new IllegalStateException("Committed");

                if (_onCommit == UNCOMMITTED)
                    _onCommit = onCommit;
                else
                {

                    Runnable previous = _onCommit;
                    _onCommit = () ->
                    {
                        notifyRunnable(previous);
                        notifyRunnable(onCommit);
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
            Runnable committed;
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
                notifyRunnable(committed);

            return new MetaData.Response(
                _request._metaData.getHttpVersion(),
                _status,
                null,
                _headers,
                -1,
                trailers);
        }

        private void notifyRunnable(Runnable runnable)
        {
            if (runnable != null)
            {
                try
                {
                    runnable.run();
                }
                catch (Throwable t)
                {
                    t.printStackTrace();
                }
            }
        }
    }
}
