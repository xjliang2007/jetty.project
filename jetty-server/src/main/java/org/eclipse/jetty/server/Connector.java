//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>A {@link Connector} accept connections and data from remote peers,
 * and allows applications to send data to remote peers, by setting up
 * the machinery needed to handle such tasks.</p>
 */
@ManagedObject("Connector Interface")
public interface Connector extends LifeCycle, Container, Graceful
{
    /**
     * @return the {@link Server} instance associated with this {@link Connector}
     */
    Server getServer();

    /**
     * @return the {@link Executor} used to submit tasks
     */
    Executor getExecutor();

    /**
     * @return the {@link Scheduler} used to schedule tasks
     */
    Scheduler getScheduler();

    /**
     * @return the {@link ByteBufferPool} to acquire buffers from and release buffers to
     */
    ByteBufferPool getByteBufferPool();

    /**
     * @param nextProtocol the next protocol
     * @return the {@link ConnectionFactory} associated with the protocol name
     */
    ConnectionFactory getConnectionFactory(String nextProtocol);

    <T> T getConnectionFactory(Class<T> factoryType);

    /**
     * @return the default {@link ConnectionFactory} associated with the default protocol name
     */
    ConnectionFactory getDefaultConnectionFactory();

    Collection<ConnectionFactory> getConnectionFactories();

    List<String> getProtocols();

    /**
     * @return the max idle timeout for connections in milliseconds
     */
    @ManagedAttribute("maximum time a connection can be idle before being closed (in ms)")
    long getIdleTimeout();

    /**
     * @return the underlying socket, channel, buffer etc. for the connector.
     */
    Object getTransport();

    /**
     * @return immutable collection of connected endpoints
     */
    Collection<EndPoint> getConnectedEndPoints();

    /**
     * Get the connector name if set.
     * <p>A {@link ContextHandler} may be configured with
     * virtual hosts in the form "@connectorName" and will only serve
     * requests from the named connector.
     *
     * @return The connector name or null.
     */
    String getName();
}
