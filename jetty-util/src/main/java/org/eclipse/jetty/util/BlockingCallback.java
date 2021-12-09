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

package org.eclipse.jetty.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides a {@link Callback} that can block the thread
 * while waiting to be completed.
 * <p>
 * A typical usage pattern is:
 * <pre>
 * void someBlockingCall(Object... args) throws IOException
 * {
 *     try(BlockingCallback blocker = new BlockingCallback())
 *     {
 *         someAsyncCall(args, blocker);
 *     }
 * }
 * </pre>
 */
public class BlockingCallback implements AutoCloseable, Callback, Runnable
{
    private static final Throwable SUCCEEDED = new Throwable();
    // TODO use CompletableFuture?
    CountDownLatch _latch = new CountDownLatch(1);
    AtomicReference<Throwable> _result = new AtomicReference<>();

    @Override
    public void close() throws Exception
    {
        _latch.await();
        Throwable result = _result.get();
        if (result == SUCCEEDED)
            return;
        throw IO.rethrow(result);
    }

    @Override
    public void run()
    {
        succeeded();
    }

    @Override
    public void succeeded()
    {
        if (_result.compareAndSet(null, SUCCEEDED))
            _latch.countDown();
    }

    @Override
    public void failed(Throwable x)
    {
        if (x == null)
            x = new Throwable();
        if (_result.compareAndSet(null, x))
            _latch.countDown();
        Callback.super.failed(x);
    }

    @Override
    public InvocationType getInvocationType()
    {
        return InvocationType.NON_BLOCKING;
    }
}
