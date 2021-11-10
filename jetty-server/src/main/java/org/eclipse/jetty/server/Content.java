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

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.util.BufferUtil;

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
        return () -> buffer;
    }

    static Content from(ByteBuffer buffer, boolean last)
    {
        return new Content()
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return buffer;
            }

            @Override
            public boolean isLast()
            {
                return last;
            }

            @Override
            public String toString()
            {
                return String.format("[%s, l=%b]", BufferUtil.toDetailString(getByteBuffer()), isLast());
            }
        };
    }

    abstract class Special implements Content
    {
        @Override
        public boolean isSpecial()
        {
            return true;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return null;
        }
    }

    Special EOF = new Special()
    {
        @Override
        public ByteBuffer getByteBuffer()
        {
            return null;
        }

        @Override
        public void release()
        {
        }

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

    class Error extends Special
    {
        private final Throwable _cause;

        public Error(Throwable cause)
        {
            _cause = cause == null ? new IOException("unknown") : cause;
        }

        Throwable getCause()
        {
            return _cause;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return null;
        }

        @Override
        public void release()
        {
        }

        @Override
        public boolean isLast()
        {
            return true;
        }

        @Override
        public String toString()
        {
            return _cause.toString();
        }
    }

    class Trailers extends Special
    {
        private final HttpFields _trailers;

        public Trailers(HttpFields trailers)
        {
            _trailers = trailers;
        }

        public HttpFields getTrailers()
        {
            return _trailers;
        }

        @Override
        public boolean isLast()
        {
            return true;
        }

        @Override
        public String toString()
        {
            return "TRAILERS";
        }
    }
}
