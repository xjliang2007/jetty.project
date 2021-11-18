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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SerializedInvokerTest
{
    Queue<Runnable> _queue = new ConcurrentLinkedQueue<>();
    Executor _executor = command -> _queue.add(command);
    SerializedInvoker _invoker;

    @BeforeEach
    public void beforeEach()
    {
        _queue.clear();
        _invoker = new SerializedInvoker(_executor);
    }

    @AfterEach
    public void afterEach()
    {
        assertThat(_queue, empty());
    }

    @Test
    public void testSimple() throws Exception
    {
        Task task1 = new Task();
        Task task2 = new Task();
        Task task3 = new Task();

        Runnable todo = _invoker.invoke(task1);
        assertNull(_invoker.invoke(task2));
        assertNull(_invoker.invoke(task3));

        assertFalse(task1.hasRun());
        assertFalse(task2.hasRun());
        assertFalse(task3.hasRun());

        todo.run();

        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertTrue(task3.hasRun());

        Task task4 = new Task();
        todo = _invoker.invoke(task4);
        todo.run();
        assertTrue(task4.hasRun());
    }

    @Test
    public void testMulti() throws Exception
    {
        Task task1 = new Task();
        Task task2 = new Task();
        Task task3 = new Task();

        Runnable todo = _invoker.invoke(null, task1, null, task2, null, task3, null);

        assertFalse(task1.hasRun());
        assertFalse(task2.hasRun());
        assertFalse(task3.hasRun());

        todo.run();

        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertTrue(task3.hasRun());

        Task task4 = new Task();
        todo = _invoker.invoke(task4);
        todo.run();
        assertTrue(task4.hasRun());
    }


    @Test
    public void testRecursive() throws Exception
    {
        Task task3 = new Task();
        Task task2 = new Task()
        {
            @Override
            public void run()
            {
                assertNull(_invoker.invoke(task3));
                super.run();
            }
        };
        Task task1 = new Task()
        {
            @Override
            public void run()
            {
                assertNull(_invoker.invoke(task2));
                super.run();
            }
        };

        Runnable todo = _invoker.invoke(task1);

        assertFalse(task1.hasRun());
        assertFalse(task2.hasRun());
        assertFalse(task3.hasRun());

        todo.run();

        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertTrue(task3.hasRun());

        Task task4 = new Task();
        todo = _invoker.invoke(task4);
        todo.run();
        assertTrue(task4.hasRun());
    }

    @Test
    public void testNonBlocking() throws Exception
    {
        Task task1 = new Task(Invocable.InvocationType.NON_BLOCKING);
        Task task2 = new Task(Invocable.InvocationType.EITHER)
        {
            @Override
            public void run()
            {
                assertTrue(Invocable.isNonBlockingInvocation());
                super.run();
            }
        };
        Task task3 = new Task(Invocable.InvocationType.BLOCKING);

        Runnable todo = _invoker.invoke(task1, task2, task3);

        assertFalse(task1.hasRun());
        assertFalse(task2.hasRun());
        assertFalse(task3.hasRun());
        assertThat(Invocable.getInvocationType(todo), equalTo(Invocable.InvocationType.NON_BLOCKING));

        todo.run();

        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertFalse(task3.hasRun());
        assertThat(_queue.size(), is(1));

        todo = _queue.poll();
        assertThat(todo, notNullValue());
        assertThat(Invocable.getInvocationType(todo), equalTo(Invocable.InvocationType.BLOCKING));

        todo.run();
        assertTrue(task1.hasRun());
        assertTrue(task2.hasRun());
        assertTrue(task3.hasRun());

        Task task4 = new Task();
        todo = _invoker.invoke(task4);
        todo.run();
        assertTrue(task4.hasRun());
    }


    public static class Task implements Runnable, Invocable
    {
        final InvocationType _type;
        CountDownLatch _run = new CountDownLatch(1);

        public Task()
        {
            this(InvocationType.BLOCKING);
        }

        public Task(InvocationType type)
        {
            _type = type;
        }

        boolean hasRun()
        {
            return _run.getCount() == 0;
        }

        @Override
        public void run()
        {
            _run.countDown();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _type;
        }
    }
}
