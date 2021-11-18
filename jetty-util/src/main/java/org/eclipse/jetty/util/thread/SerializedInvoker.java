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

package org.eclipse.jetty.util.thread;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SerializedInvoker implements Runnable, Invocable
{
    private static final Logger LOG = LoggerFactory.getLogger(SerializedInvoker.class);

    private final Executor _executor;
    private final AutoLock _lock = new AutoLock();
    private final Deque<Runnable> _queue = new ArrayDeque<>();
    private Invocable.InvocationType _runAs;
    private Invocable.InvocationType _runningAs;

    public SerializedInvoker(Executor executor)
    {
        _executor = executor;
    }

    public Runnable invoke(Runnable... runnable)
    {
        try (AutoLock ignored = _lock.lock())
        {
            // Add Runnables to the queue and combine invocation types
            for (Runnable r : runnable)
            {
                if (r != null)
                {
                    _runAs = Invocable.combine(_runAs, Invocable.getInvocationType(r));
                    _queue.add(r);
                }
            }

            // return null if this invoker is already running
            if (_runningAs != null)
                return null;

            // return this invoker to run as the current invocation type
            _runningAs = _runAs;
            return this;
        }
    }

    @Override
    public void run()
    {
        // We are running in blocking mode is BLOCKING or it is EITHER and we were not invoked non-blocking
        boolean runningBlocking = _runningAs == InvocationType.BLOCKING || _runningAs == InvocationType.EITHER && !Invocable.isNonBlockingInvocation();

        // loop through all the queued tasks
        while (true)
        {
            Runnable task;
            try (AutoLock ignored = _lock.lock())
            {
                // Get the next task off the queue
                task = _queue.pollFirst();
                if (task == null)
                {
                    // if no more tasks, then end invocation
                    _runningAs = null;
                    return;
                }
            }

            // invoke the task
            try
            {
                // if we are running in blocking mode
                if (runningBlocking)
                {
                    // we can just call the task directly
                    task.run();
                }
                else
                {
                    // we are running in non blocking mode
                    switch (Invocable.getInvocationType(task))
                    {
                        case BLOCKING:
                            // The task is blocking, so put it back on the queue and execute this invoker
                            _queue.addFirst(task);
                            _runningAs = InvocationType.BLOCKING;
                            _executor.execute(this);
                            return;

                        case NON_BLOCKING:
                            // the task is also non_blocking, so just run it
                            task.run();
                            break;

                        case EITHER:
                            // the task is either, so invoke it in non blocking mode
                            Invocable.invokeNonBlocking(task);
                            break;
                    }
                }
            }
            catch (Throwable t)
            {
                LOG.warn("Invocation failed {} {}", task, t);
            }
        }
    }

    @Override
    public InvocationType getInvocationType()
    {
        InvocationType type = _runningAs;
        return type == null ? InvocationType.BLOCKING : type;
    }
}
