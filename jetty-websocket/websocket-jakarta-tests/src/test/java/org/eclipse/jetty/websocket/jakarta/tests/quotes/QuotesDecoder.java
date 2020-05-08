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

package org.eclipse.jetty.websocket.jakarta.tests.quotes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import jakarta.websocket.EndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuotesDecoder implements Decoder.TextStream<Quotes>
{
    private static final Logger LOG = LoggerFactory.getLogger(QuotesDecoder.class);

    @SuppressWarnings("RedundantThrows")
    @Override
    public Quotes decode(Reader reader) throws DecodeException, IOException
    {
        Quotes quotes = new Quotes();
        try (BufferedReader buf = new BufferedReader(reader))
        {
            LOG.debug("decode() begin");
            String line;
            while ((line = buf.readLine()) != null)
            {
                LOG.debug("decode() line = {}", line);
                switch (line.charAt(0))
                {
                    case 'a':
                        quotes.setAuthor(line.substring(2));
                        break;
                    case 'q':
                        quotes.addQuote(line.substring(2));
                        break;
                }
            }
            LOG.debug("decode() complete");
        }
        return quotes;
    }

    @Override
    public void destroy()
    {
    }

    @Override
    public void init(EndpointConfig config)
    {
    }
}
