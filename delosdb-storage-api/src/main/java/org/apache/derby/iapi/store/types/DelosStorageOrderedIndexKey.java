/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageOrderedIndexKey

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package org.apache.derby.iapi.store.types;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.util.Objects;

import org.apache.derby.shared.common.error.StandardException;

/**
 * Typed key boundary for page-backed MVCC ordered index lookups.
 *
 * <p>The ordered index page store persists keys as text for now, but the text is
 * a typed envelope rather than the raw Derby {@code getString()} value.  This
 * keeps equality/range lookups from accidentally using lexical string ordering
 * for numeric SQL values while the physical ordered index format is still being
 * evolved.</p>
 */
public final class DelosStorageOrderedIndexKey
{
    private static final char SEPARATOR = '|';

    private DelosStorageOrderedIndexKey()
    {
    }

    public static String encode(StoreDataValue value) throws StandardException
    {
        Objects.requireNonNull(value, "value");
        if (StoreTypeUtil.isNull(value))
        {
            return encodeNull();
        }
        Object object = StoreTypeUtil.getObject(value);
        if (object == value && value instanceof StoreValueOperations operations)
        {
            object = operations.getString();
        }
        if (object == null)
        {
            return encodeNull();
        }
        return encodeObject(object);
    }

    public static int compare(String left, String right)
    {
        EncodedKey leftKey = EncodedKey.parse(left);
        EncodedKey rightKey = EncodedKey.parse(right);
        int kindComparison = Integer.compare(leftKey.kind().order(), rightKey.kind().order());
        if (kindComparison != 0)
        {
            return kindComparison;
        }
        return switch (leftKey.kind())
        {
            case NULL -> 0;
            case INTEGER -> compareIntegers(leftKey.payload(), rightKey.payload());
            case DECIMAL -> compareDecimals(leftKey.payload(), rightKey.payload());
            case FLOAT -> compareFloats(leftKey.payload(), rightKey.payload());
            case TEMPORAL, TEXT, LEGACY -> leftKey.payload().compareTo(rightKey.payload());
        };
    }

    public static boolean isEncoded(String key)
    {
        return EncodedKey.hasTypedEnvelope(key);
    }

    public static String display(String key)
    {
        if (!EncodedKey.hasTypedEnvelope(key))
        {
            return key;
        }
        return EncodedKey.parse(key).payload();
    }

    private static String encodeObject(Object object)
    {
        if (object instanceof BigDecimal decimal)
        {
            return envelope(Kind.DECIMAL, canonicalDecimal(decimal));
        }
        if (object instanceof BigInteger integer)
        {
            return envelope(Kind.INTEGER, integer.toString());
        }
        if (object instanceof Byte || object instanceof Short
                || object instanceof Integer || object instanceof Long)
        {
            return envelope(Kind.INTEGER, Long.toString(((Number) object).longValue()));
        }
        if (object instanceof Float || object instanceof Double)
        {
            return envelope(Kind.FLOAT, Double.toString(((Number) object).doubleValue()));
        }
        if (object instanceof Date || object instanceof Time || object instanceof Timestamp
                || object instanceof TemporalAccessor)
        {
            return envelope(Kind.TEMPORAL, object.toString());
        }
        if (object instanceof CharSequence text)
        {
            return envelope(Kind.TEXT, text.toString());
        }
        return envelope(Kind.TEXT, object.toString());
    }

    private static String encodeNull()
    {
        return envelope(Kind.NULL, "");
    }

    private static String envelope(Kind kind, String payload)
    {
        return kind.code() + String.valueOf(SEPARATOR) + Objects.requireNonNull(payload, "payload");
    }

    private static int compareIntegers(String left, String right)
    {
        try
        {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        catch (NumberFormatException e)
        {
            return left.compareTo(right);
        }
    }

    private static int compareDecimals(String left, String right)
    {
        try
        {
            return new BigDecimal(left).compareTo(new BigDecimal(right));
        }
        catch (NumberFormatException e)
        {
            return left.compareTo(right);
        }
    }

    private static int compareFloats(String left, String right)
    {
        try
        {
            return Double.compare(Double.parseDouble(left), Double.parseDouble(right));
        }
        catch (NumberFormatException e)
        {
            return left.compareTo(right);
        }
    }

    private static String canonicalDecimal(BigDecimal decimal)
    {
        BigDecimal normalized = decimal.stripTrailingZeros();
        if (BigDecimal.ZERO.compareTo(normalized) == 0)
        {
            return "0";
        }
        return normalized.toPlainString();
    }

    private enum Kind
    {
        NULL('N', 0),
        INTEGER('I', 1),
        DECIMAL('D', 2),
        FLOAT('F', 3),
        TEMPORAL('T', 4),
        TEXT('S', 5),
        LEGACY('L', 6);

        private final char code;
        private final int order;

        Kind(char code, int order)
        {
            this.code = code;
            this.order = order;
        }

        char code()
        {
            return code;
        }

        int order()
        {
            return order;
        }

        static Kind fromCode(char code)
        {
            for (Kind kind : values())
            {
                if (kind.code == code)
                {
                    return kind;
                }
            }
            return LEGACY;
        }
    }

    private record EncodedKey(Kind kind, String payload)
    {
        static EncodedKey parse(String key)
        {
            Objects.requireNonNull(key, "key");
            if (!hasTypedEnvelope(key))
            {
                return new EncodedKey(Kind.LEGACY, key);
            }
            return new EncodedKey(Kind.fromCode(key.charAt(0)), key.substring(2));
        }

        static boolean hasTypedEnvelope(String key)
        {
            return key != null && key.length() >= 2 && key.charAt(1) == SEPARATOR;
        }
    }
}
