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

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.LoggerFactory;

/**
 * Ensures serial invocation of submitted tasks.
 * <p>
 * This class was inspired by the public domain class
 * <a href="https://github.com/jroper/reactive-streams-servlet/blob/master/reactive-streams-servlet/src/main/java/org/reactivestreams/servlet/NonBlockingMutexExecutor.java">NonBlockingMutexExecutor</a>
 * </p>
 */
public class SerializedInvoker
{
    private final AtomicReference<Link> _tail = new AtomicReference<>();
    private final Executor _executor;

    public SerializedInvoker(Executor executor)
    {
        _executor = executor;
    }

    public Runnable invoke(Runnable task)
    {
        if (task == null)
            return null;
        Link link = new Link(task);
        Link lastButOne = _tail.getAndSet(link);
        if (lastButOne == null)
            return link;
        lastButOne._next.lazySet(link);
        return null;
    }

    public Runnable invoke(Runnable... task)
    {
        Runnable runnable = null;
        for (Runnable run : task)
        {
            if (run != null)
            {
                if (runnable == null)
                    runnable = invoke(run);
                else
                    invoke(run);
            }
        }
        return runnable;
    }

    public void execute(Runnable task)
    {
        Runnable todo = invoke(task);
        if (todo != null)
            _executor.execute(todo);
    }

    public void execute(Runnable... tasks)
    {
        Runnable todo = invoke(tasks);
        if (todo != null)
            _executor.execute(todo);
    }

    protected void onError(Runnable task, Throwable t)
    {
        try
        {
            if (task instanceof ErrorHandlingTask)
                ((ErrorHandlingTask)task).accept(t);
        }
        catch (Throwable x)
        {
            if (x != t)
                t.addSuppressed(x);
        }
        LoggerFactory.getLogger(task.getClass()).error("Error", t);
    }

    private void invoke(Link link, boolean runningBlocking)
    {
        while (link != null)
        {
            try
            {
                // if we are running in blocking mode
                if (runningBlocking)
                {
                    // we can just call the task directly
                    link._task.run();
                }
                else
                {
                    // we are running in non blocking mode
                    switch (Invocable.getInvocationType(link._task))
                    {
                        case BLOCKING:
                            // The task is blocking, so execute it
                            _executor.execute(link);
                            return;

                        case NON_BLOCKING:
                            // the task is also non_blocking, so just run it
                            link._task.run();
                            break;

                        case EITHER:
                            // the task is either, so invoke it in non blocking mode
                            Invocable.invokeNonBlocking(link._task);
                            break;
                    }
                }
            }
            catch (Throwable t)
            {
                onError(link._task, t);
            }

            // Are we the current the last Link?
            if (_tail.compareAndSet(link, null))
                link = null;
            else
            {
                // not the last task, so its next link will eventually be set
                Link next = link._next.get();
                while (next == null)
                {
                    Thread.onSpinWait();
                    next = link._next.get();
                }
                link = next;
            }
        }
    }

    private class Link implements Runnable, Invocable
    {
        private final Runnable _task;
        private final AtomicReference<Link> _next = new AtomicReference<>();

        public Link(Runnable task)
        {
            _task = task;
        }

        @Override
        public void run()
        {
            // We are running in blocking mode if BLOCKING or it is EITHER and we were not invoked non-blocking
            Invocable.InvocationType runningAs = Invocable.getInvocationType(_task);
            boolean runningBlocking = runningAs == Invocable.InvocationType.BLOCKING || runningAs == Invocable.InvocationType.EITHER && !Invocable.isNonBlockingInvocation();
            invoke(this, runningBlocking);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return Invocable.getInvocationType(_task);
        }
    }

    /**
     * Error handling task
     * <p>If a submitted task implements this interface, it will be passed
     * any exceptions thrown when running the task.</p>
     */
    public interface ErrorHandlingTask extends Runnable, Consumer<Throwable>
    {}
}
