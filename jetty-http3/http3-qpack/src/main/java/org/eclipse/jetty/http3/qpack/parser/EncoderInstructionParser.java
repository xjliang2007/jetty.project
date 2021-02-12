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

package org.eclipse.jetty.http3.qpack.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.QpackException;

/**
 * Receives instructions coming from the remote Decoder as a sequence of unframed instructions.
 */
public class EncoderInstructionParser
{
    private final Handler _handler;
    private final NBitStringParser _stringParser;
    private final NBitIntegerParser _integerParser;
    private State _state = State.PARSING;
    private Operation _operation = Operation.NONE;

    private boolean _referenceDynamicTable;
    private int _index;
    private String _name;

    private enum State
    {
        PARSING,
        SET_CAPACITY,
        REFERENCED_NAME,
        LITERAL_NAME,
        DUPLICATE
    }

    private enum Operation
    {
        NONE,
        INDEX,
        NAME,
        VALUE,
    }

    public interface Handler
    {
        void onSetDynamicTableCapacity(int capacity);

        void onDuplicate(int index);

        void onInsertNameWithReference(int nameIndex, boolean isDynamicTableIndex, String value);

        void onInsertWithLiteralName(String name, String value);
    }

    public EncoderInstructionParser(Handler handler)
    {
        _handler = handler;
        _stringParser = new NBitStringParser();
        _integerParser = new NBitIntegerParser();
    }

    public void parse(ByteBuffer buffer) throws QpackException
    {
        if (buffer == null || !buffer.hasRemaining())
            return;

        switch (_state)
        {
            case PARSING:
                byte firstByte = buffer.get(buffer.position());
                if ((firstByte & 0x80) != 0)
                {
                    _state = State.REFERENCED_NAME;
                    parseInsertNameWithReference(buffer);
                }
                else if ((firstByte & 0x40) != 0)
                {
                    _state = State.LITERAL_NAME;
                    parseInsertWithLiteralName(buffer);
                }
                else if ((firstByte & 0x20) != 0)
                {
                    _state = State.SET_CAPACITY;
                    parseSetDynamicTableCapacity(buffer);
                }
                else
                {
                    _state = State.DUPLICATE;
                    parseDuplicate(buffer);
                }
                break;

            case SET_CAPACITY:
                parseSetDynamicTableCapacity(buffer);
                break;

            case DUPLICATE:
                parseDuplicate(buffer);
                break;

            case LITERAL_NAME:
                parseInsertWithLiteralName(buffer);
                break;

            case REFERENCED_NAME:
                parseInsertNameWithReference(buffer);
                break;

            default:
                throw new IllegalStateException(_state.name());
        }
    }

    private void parseInsertNameWithReference(ByteBuffer buffer) throws QpackException
    {
        while (true)
        {
            switch (_operation)
            {
                case NONE:
                    byte firstByte = buffer.get(buffer.position());
                    _referenceDynamicTable = (firstByte & 0x40) == 0;
                    _operation = Operation.INDEX;
                    continue;

                case INDEX:
                    _index = _integerParser.decode(buffer, 6);
                    if (_index < 0)
                        return;

                    _stringParser.setPrefix(8);
                    _operation = Operation.VALUE;
                    continue;

                case VALUE:
                    String value = _stringParser.decode(buffer);
                    if (value == null)
                        return;
                    _operation = Operation.NONE;
                    _state = State.PARSING;
                    _handler.onInsertNameWithReference(_index, _referenceDynamicTable, value);
                    return;

                default:
                    throw new IllegalStateException(_operation.name());
            }
        }
    }

    private void parseInsertWithLiteralName(ByteBuffer buffer) throws QpackException
    {
        while (true)
        {
            switch (_operation)
            {
                case NONE:
                    _stringParser.setPrefix(6);
                    _operation = Operation.NAME;
                    continue;

                case NAME:
                    _name = _stringParser.decode(buffer);
                    if (_name == null)
                        return;

                    _stringParser.setPrefix(8);
                    _operation = Operation.VALUE;
                    continue;

                case VALUE:
                    String value = _stringParser.decode(buffer);
                    if (value == null)
                        return;

                    _operation = Operation.NONE;
                    _state = State.PARSING;
                    _handler.onInsertWithLiteralName(_name, value);
                    return;

                default:
                    throw new IllegalStateException(_operation.name());
            }
        }
    }

    private void parseDuplicate(ByteBuffer buffer)
    {
        int index = _integerParser.decode(buffer, 5);
        if (index >= 0)
        {
            _state = State.PARSING;
            _handler.onDuplicate(index);
        }
    }

    private void parseSetDynamicTableCapacity(ByteBuffer buffer)
    {
        int capacity = _integerParser.decode(buffer, 5);
        if (capacity >= 0)
        {
            _state = State.PARSING;
            _handler.onSetDynamicTableCapacity(capacity);
        }
    }
}