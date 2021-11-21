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

import org.eclipse.jetty.util.thread.Invocable.InvocationType;
import org.slf4j.LoggerFactory;

/**
 * Ensures serial invocation of submitted tasks, respecting the {@link InvocationType}
 * <p>
 * The {@link InvocationType} of the {@link Runnable} returned from this class is
 * the the result of {@link Invocable#combine(InvocationType, InvocationType)} on all the passed tasks
 * at the time that the {@link Runnable} was generated.  If subsequent tasks are invoked, then they may need to be executed
 * if their {@link InvocationType} is not compatible with the calling thread (as
 * per {@link Invocable#isNonBlockingInvocation()}.
 * <p>
 * This class was inspired by the public domain class
 * <a href="https://github.com/jroper/reactive-streams-servlet/blob/master/reactive-streams-servlet/src/main/java/org/reactivestreams/servlet/NonBlockingMutexExecutor.java">NonBlockingMutexExecutor</a>
 * </p>
 */
public class SerializedExecutor implements Executor
{
    private final AtomicReference<Link> _tail = new AtomicReference<>();
    private final Executor _executor;

    public SerializedExecutor()
    {
        this(Runnable::run);
    }

    public SerializedExecutor(Executor executor)
    {
        _executor = executor;
    }

    /**
     * Arrange for a task to be invoked, mutually excluded from other tasks.
     * @param task The task to invoke
     * @return A Runnable that must be called to invoke the passed task and possibly other tasks. Null if the
     *         task will be invoked by another caller.
     */
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

    /**
     * Arrange for tasks to be invoked, mutually excluded from other tasks.
     * @param tasks The tasks to invoke
     * @return A Runnable that must be called to invoke the passed tasks and possibly other tasks. Null if the
     *         tasks will be invoked by another caller.
     */
    public Runnable invoke(Runnable... tasks)
    {
        Runnable runnable = null;
        for (Runnable run : tasks)
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

    /**
     * Arrange for a task to be executed, mutually excluded from other tasks.
     * This is equivalent to executing any {@link Runnable} returned from {@link #invoke(Runnable)}
     * @param task The task to invoke
     */
    @Override
    public void execute(Runnable task)
    {
        Runnable todo = invoke(task);
        if (todo != null)
            _executor.execute(todo);
    }

    /**
     * Arrange for tasks to be executed, mutually excluded from other tasks.
     * This is equivalent to executing any {@link Runnable} returned from {@link #invoke(Runnable...)}
     * @param tasks The tasks to invoke
     */
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
            InvocationType runningAs = Invocable.getInvocationType(_task);
            boolean runningBlocking = runningAs == InvocationType.BLOCKING || runningAs == InvocationType.EITHER && !Invocable.isNonBlockingInvocation();
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
