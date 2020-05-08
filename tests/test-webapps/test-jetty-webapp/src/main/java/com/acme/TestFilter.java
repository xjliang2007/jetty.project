//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package com.acme;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TestFilter.
 *
 * This filter checks for a none local request, and if the init parameter
 * "remote" is not set to true, then all non local requests are forwarded
 * to /remote.html
 */
public class TestFilter implements Filter
{
    private static final Logger LOG = LoggerFactory.getLogger(TestFilter.class);

    private boolean _remote;
    private ServletContext _context;
    private final Set<String> _allowed = new HashSet<String>();

    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
        _context = filterConfig.getServletContext();
        _remote = Boolean.parseBoolean(filterConfig.getInitParameter("remote"));
        _allowed.add("/favicon.ico");
        _allowed.add("/jetty_banner.gif");
        _allowed.add("/remote.html");

        LOG.debug("TestFilter#remote=" + _remote);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException
    {
        String from = request.getRemoteAddr();
        String to = request.getLocalAddr();
        String path = ((HttpServletRequest)request).getServletPath();

        if (!_remote && !_allowed.contains(path) && !from.equals(to))
        {
            _context.getRequestDispatcher("/remote.html").forward(request, response);
            return;
        }

        Integer oldValue = null;
        ServletRequest r = request;
        while (r instanceof ServletRequestWrapper)
        {
            r = ((ServletRequestWrapper)r).getRequest();
        }

        try
        {
            oldValue = (Integer)request.getAttribute("testFilter");

            Integer value = (oldValue == null) ? 1 : oldValue + 1;

            request.setAttribute("testFilter", value);

            String qString = ((HttpServletRequest)request).getQueryString();
            if (qString != null && qString.indexOf("wrap") >= 0)
            {
                request = new HttpServletRequestWrapper((HttpServletRequest)request);
            }
            _context.setAttribute("request" + r.hashCode(), value);

            chain.doFilter(request, response);
        }
        finally
        {
            request.setAttribute("testFilter", oldValue);
            _context.setAttribute("request" + r.hashCode(), oldValue);
        }
    }

    @Override
    public void destroy()
    {
    }
}
