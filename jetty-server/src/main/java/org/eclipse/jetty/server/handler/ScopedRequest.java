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

package org.eclipse.jetty.server.handler;

import org.eclipse.jetty.server.Request;

public class ScopedRequest extends Request.Wrapper
{
    private final String _pathInContext;
    private final ContextHandler.Context _context;

    protected ScopedRequest(ContextHandler.Context context, Request wrapped, String pathInContext)
    {
        super(wrapped);
        _pathInContext = pathInContext;
        this._context = context;
    }

    public ContextHandler.Context getContext()
    {
        return _context;
    }

    public String getPath()
    {
        return _pathInContext;
    }

    @Override
    public Object getAttribute(String name)
    {
        // return some hidden attributes for requestLog
        switch (name)
        {
            case "o.e.j.s.h.ScopedRequest.contextPath":
                return _context.getContextPath();
            case "o.e.j.s.h.ScopedRequest.pathInContext":
                return _pathInContext;
            default:
                return super.getAttribute(name);
        }
    }
}
