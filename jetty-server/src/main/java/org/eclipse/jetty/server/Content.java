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
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.Utf8StringBuilder;

/**
 * The Content abstract is based on what is already used in several places.
 * It allows EOF and Error flows to be unified with content data. This allows
 * the semantics of multiple methods like flush, close, onError, etc. to be
 * included in the read/write APIs.
 */
public interface Content
{
    ByteBuffer getByteBuffer();

    default boolean isSpecial()
    {
        return false;
    }

    default void checkError() throws IOException
    {
    }

    default void release()
    {
    }

    default boolean isLast()
    {
        return false;
    }

    default int remaining()
    {
        ByteBuffer b = getByteBuffer();
        return b == null ? 0 : b.remaining();
    }

    default boolean hasRemaining()
    {
        ByteBuffer b = getByteBuffer();
        return b != null && b.hasRemaining();
    }

    default boolean isEmpty()
    {
        return !hasRemaining();
    }

    default int fill(byte[] buffer, int offset, int length)
    {
        ByteBuffer b = getByteBuffer();
        if (b == null || !b.hasRemaining())
            return 0;
        length = Math.min(length, b.remaining());
        b.get(buffer, offset, length);
        return length;
    }

    static Content from(ByteBuffer buffer)
    {
        return from(buffer, false);
    }

    static Content from(ByteBuffer buffer, boolean last)
    {
        return new Abstract(false, last)
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return buffer;
            }
        };
    }

    static Content last(Content content)
    {
        if (content == null)
            return EOF;
        if (content.isLast())
            return content;
        return new Abstract(content.isSpecial(), true)
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return content.getByteBuffer();
            }

            @Override
            public void release()
            {
                content.release();
            }
        };
    }

    /**
     * Compute the next content from the current content.
     * @param content The current content
     * @return The next content if known, else null
     */
    static Content next(Content content)
    {
        if (content != null)
        {
            if (content instanceof Trailers)
                return EOF;
            if (content.isSpecial())
                return content;
            if (content.isLast())
                return EOF;
        }
        return null;
    }

    abstract class Abstract implements Content
    {
        private final boolean _special;
        private final boolean _last;

        protected Abstract(boolean special, boolean last)
        {
            _special = special;
            _last = last;
        }

        @Override
        public boolean isSpecial()
        {
            return _special;
        }

        @Override
        public boolean isLast()
        {
            return _last;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return null;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s,s=%b,l=%b}",
                getClass().getName(),
                hashCode(),
                BufferUtil.toDetailString(getByteBuffer()),
                isSpecial(),
                isLast());
        }
    }

    Content EOF = new Abstract(true, true)
    {
        @Override
        public boolean isLast()
        {
            return true;
        }

        @Override
        public String toString()
        {
            return "EOF";
        }
    };

    class Error extends Abstract
    {
        private final Throwable _cause;

        public Error(Throwable cause)
        {
            this (cause, true);
        }

        public Error(Throwable cause, boolean last)
        {
            super(true, last);
            _cause = cause == null ? new IOException("unknown") : cause;
        }

        @Override
        public void checkError() throws IOException
        {
            throw IO.rethrow(_cause);
        }

        public Throwable getCause()
        {
            return _cause;
        }

        @Override
        public String toString()
        {
            return _cause.toString();
        }
    }

    class Trailers extends Abstract
    {
        private final HttpFields _trailers;

        public Trailers(HttpFields trailers)
        {
            super(true, true);
            _trailers = trailers;
        }

        public HttpFields getTrailers()
        {
            return _trailers;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{t=%d,s=%b,l=%b}",
                getClass().getName(),
                hashCode(),
                _trailers.size(),
                isSpecial(),
                isLast());
        }
    }

    interface Producer
    {
        Content readContent();

        void demandContent();
    }

    interface Provider
    {
        void content(Consumer<Producer> provider);
    }

    abstract class Processor implements Provider
    {
        protected Processor(Provider provider)
        {
            provider.content(this::onContentAvailable);
        }

        protected abstract void onContentAvailable(Content.Producer producer);
    }

    class EmptyProvider implements Provider
    {
        private final Producer _producer = new Producer()
        {
            @Override
            public Content readContent()
            {
                return EOF;
            }

            @Override
            public void demandContent()
            {
                _onContentAvailable.accept(this);
            }
        };

        private volatile Consumer<Producer> _onContentAvailable;

        @Override
        public void content(Consumer<Producer> onContentAvailable)
        {
            Objects.requireNonNull(onContentAvailable);
            _onContentAvailable = onContentAvailable;
            onContentAvailable.accept(_producer);
        }
    }

    class ByteReader extends Promise.Completable<ByteBuffer> implements Consumer<Producer>
    {
        ByteBufferAccumulator _out = new ByteBufferAccumulator(); // TODO from a pool?

        @Override
        public void accept(Producer provider)
        {
            while (true)
            {
                Content c = provider.readContent();
                if (c == null)
                {
                    provider.demandContent();
                    return;
                }
                if (c.hasRemaining())
                {
                    _out.copyBuffer(c.getByteBuffer());
                    c.release();
                }
                if (c.isLast())
                {
                    if (c instanceof Error)
                        failed(((Error)c).getCause());
                    else
                        succeeded(_out.takeByteBuffer());
                    return;
                }
            }
        }
    }

    class Utf8StringReader extends Promise.Completable<String> implements Consumer<Producer>
    {
        Utf8StringBuilder _out = new Utf8StringBuilder();

        @Override
        public void accept(Producer provider)
        {
            while (true)
            {
                Content c = provider.readContent();
                if (c == null)
                {
                    provider.demandContent();
                    return;
                }
                if (c.hasRemaining())
                {
                    _out.append(c.getByteBuffer());
                    c.release();
                }
                if (c.isLast())
                {
                    if (c instanceof Error)
                        failed(((Error)c).getCause());
                    else
                        succeeded(_out.toString());
                    return;
                }
            }
        }
    }
}
