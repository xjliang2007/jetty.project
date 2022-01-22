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

import java.nio.channels.ReadPendingException;
import java.util.function.Consumer;

import org.eclipse.jetty.util.thread.AutoLock;

public abstract class ContentProcessor extends Content.Processor
{
    private final AutoLock _lock = new AutoLock();
    private final ProcessProducer _outputProducer = new ProcessProducer();
    private Content.Producer _inputProducer;
    private boolean _reading;
    private boolean _iterating;
    private boolean _available;
    private Content _output;
    private Consumer<Content.Producer> _onContentAvailable;
    private boolean _demanding;

    public ContentProcessor(Content.Provider provider)
    {
        super(provider);
    }

    @Override
    public void content(Consumer<Content.Producer> onContentAvailable)
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (_demanding)
                throw new IllegalStateException("Demand pending");
            _onContentAvailable = onContentAvailable;
        }
        onContentAvailable.accept(_outputProducer);
    }

    private class ProcessProducer implements Content.Producer
    {
        @Override
        public Content readContent()
        {
            boolean available;
            Content.Producer inputProducer;
            try (AutoLock ignored = _lock.lock())
            {
                if (_reading)
                    throw new ReadPendingException();

                if (_output != null)
                {
                    Content output = _output;
                    _output = null;
                    return output;
                }

                if (_inputProducer == null)
                    return null;
                inputProducer = _inputProducer;

                available = _available;
                _available = false;
                _reading = true;
            }

            Content input = null;
            try
            {
                while (true)
                {
                    Content output = available ? null : process(null);
                    if (output != null)
                        return output;
                    available = false;
                    input = inputProducer.readContent();
                    output = process(input);
                    if (output != null)
                        return output;
                    if (input == null)
                        return null;
                }
            }
            catch (Throwable t)
            {
                return new Content.Error(t, input != null && input.isLast());
            }
            finally
            {
                try (AutoLock ignored = _lock.lock())
                {
                    _reading = false;
                }
            }
        }

        @Override
        public void demandContent()
        {
            iterate(null);
        }
    }

    private void iterate(Content.Producer inputProducer)
    {
        boolean available = inputProducer != null;
        Consumer<Content.Producer> onContentAvailable = null;

        try (AutoLock ignored = _lock.lock())
        {
            if (_reading)
                throw new IllegalStateException("read pending");

            if (inputProducer == null)
            {
                if (_inputProducer == null)
                    return;
                inputProducer = _inputProducer;
                _demanding = true;
            }
            else
            {
                if (_inputProducer != null && inputProducer != _inputProducer)
                    throw new IllegalStateException("mixed input producer?");
                _inputProducer = inputProducer;
            }

            _available |= available;

            if (_iterating)
                return;

            if (_output == null)
            {
                available = _available;
                _available = false;
            }
            else
            {
                onContentAvailable = _onContentAvailable;
                _demanding = false;
            }

            _iterating = true;
        }

        Content output = null;
        boolean iterating = true;
        while (iterating)
        {
            if (onContentAvailable != null)
            {
                Throwable error = null;
                try
                {
                    onContentAvailable.accept(_outputProducer);
                }
                catch (Throwable t)
                {
                    error = t;
                }
                finally
                {
                    onContentAvailable = null;
                    try (AutoLock ignored = _lock.lock())
                    {
                        if (error != null)
                        {
                            if (_output != null)
                                _output.release();
                            _output = new Content.Error(error, false);
                        }

                        if (!_demanding)
                            _iterating = iterating = false;
                        else if (_output != null)
                        {
                            onContentAvailable = _onContentAvailable;
                            _demanding = false;
                        }
                    }
                }
            }
            else
            {
                Content input = null;
                try
                {
                    output = available ? null : process(null);
                    if (output == null)
                    {
                        input = inputProducer.readContent();
                        output = process(input);
                        available = false;
                        if (output == null && input == null)
                            inputProducer.demandContent();
                    }
                }
                catch (Throwable t)
                {
                    if (output != null)
                        output.release();
                    output = new Content.Error(t, input != null && input.isLast());
                    available = false;
                }
                finally
                {
                    try (AutoLock ignored = _lock.lock())
                    {
                        _output = output;
                        if (_output == null)
                        {
                            if (_available)
                            {
                                available = true;
                                _available = false;
                            }
                            else if (input == null)
                            {
                                iterating = false;
                            }
                        }
                        else if (_onContentAvailable == null)
                        {
                            iterating = false;
                        }
                        else
                        {
                            onContentAvailable = _onContentAvailable;
                            _onContentAvailable = null;
                        }
                        _iterating = iterating;
                    }
                }
            }
        }
    }

    protected void onContentAvailable(Content.Producer producer)
    {
        iterate(producer);
    }

    protected abstract Content process(Content content);
}
