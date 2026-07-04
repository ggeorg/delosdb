/*

   Derby - Class org.apache.derby.iapi.store.types.StoreValueCopySupport

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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Central copy/clone helpers for opaque Derby store values.
 *
 * <p>Delos storage modules intentionally compile below the SQL type module,
 * but inherited Derby rows still carry engine SQL value implementations at
 * runtime.  Keep clone/copy/string-access fallback rules in this boundary
 * helper so MVCC and bridge paths do not grow independent reflective copies.</p>
 */
public final class StoreValueCopySupport
{
    private StoreValueCopySupport()
    {
    }

    public static StoreDataValue[] cloneRow(StoreDataValue[] row)
        throws StandardException
    {
        if (row == null)
        {
            return new StoreDataValue[0];
        }
        StoreDataValue[] copy = new StoreDataValue[row.length];
        for (int i = 0; i < row.length; i++)
        {
            copy[i] = cloneValue(row[i]);
        }
        return copy;
    }

    public static StoreDataValue[] replacementRow(
            StoreDataValue[] current,
            StoreDataValue[] replacement,
            FormatableBitSet validColumns)
        throws StandardException
    {
        if (validColumns == null)
        {
            return cloneRow(replacement);
        }
        StoreDataValue[] merged = cloneRow(current);
        int nextColumn = -1;
        while ((nextColumn = validColumns.anySetBit(nextColumn)) >= 0)
        {
            if (nextColumn < merged.length
                    && replacement != null
                    && nextColumn < replacement.length)
            {
                merged[nextColumn] = cloneValue(replacement[nextColumn]);
            }
        }
        return merged;
    }

    public static void copyRow(
            StoreDataValue[] source,
            StoreDataValue[] destination,
            FormatableBitSet validColumns)
        throws StandardException
    {
        if (destination == null || source == null)
        {
            return;
        }
        for (int i = 0; i < destination.length && i < source.length; i++)
        {
            if (validColumns != null && !validColumns.isSet(i))
            {
                continue;
            }
            StoreDataValue value = source[i];
            if (!copyValue(destination[i], value))
            {
                destination[i] = cloneValue(value);
            }
        }
    }


    public static StoreDataValue cloneHolderOrSelf(StoreDataValue value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof StoreValueOperations operations)
        {
            return operations.cloneHolder();
        }
        StoreDataValue cloned = cloneHolderThroughTypeSupport(value);
        if (cloned != null)
        {
            return cloned;
        }
        cloned = storeDataValueReflectively(value, "cloneHolder");
        return cloned == null ? value : cloned;
    }

    public static StoreDataValue cloneValueOrSelf(StoreDataValue value)
        throws StandardException
    {
        if (value == null)
        {
            return null;
        }
        StoreDataValue cloned = tryCloneValue(value);
        return cloned == null ? value : cloned;
    }

    public static StoreDataValue cloneValue(StoreDataValue value)
        throws StandardException
    {
        if (value == null)
        {
            return null;
        }
        StoreDataValue cloned = tryCloneValue(value);
        if (cloned != null)
        {
            return cloned;
        }
        throw new IllegalArgumentException(
                "Delos storage requires cloneable StoreDataValue: "
                + value.getClass().getName());
    }

    public static boolean copyValue(StoreDataValue destination, StoreDataValue source)
        throws StandardException
    {
        if (destination == null)
        {
            return false;
        }
        if (destination instanceof StoreValueOperations operations)
        {
            operations.setValue(source);
            return true;
        }
        if (source == null)
        {
            return false;
        }
        if (copyValueThroughTypeSupport(destination, source))
        {
            return true;
        }
        return copySqlValueReflectively(destination, source);
    }

    public static String rawStringKey(StoreDataValue value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof StoreValueOperations operations)
        {
            try
            {
                return rawStringKey(operations.getString());
            }
            catch (StandardException e)
            {
                throw new IllegalStateException("Cannot derive store value key from "
                        + value.getClass().getName(), e);
            }
        }
        Optional<Method> getString = publicNoArgMethod(value.getClass(), "getString");
        if (getString.isEmpty())
        {
            return value.toString();
        }
        try
        {
            Object result = getString.get().invoke(value);
            return rawStringKey(result);
        }
        catch (IllegalAccessException e)
        {
            // Some Derby LOB values inherit public methods from package-private
            // implementation classes. Raw row/candidate diagnostics must not
            // fail commits for those values; keep the existing fallback shape.
            return value.toString();
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException)
            {
                throw runtimeException;
            }
            if (cause instanceof Error error)
            {
                throw error;
            }
            return value.toString();
        }
    }

    private static String rawStringKey(Object value)
    {
        return value == null ? "<null>" : value.toString();
    }


    private static StoreDataValue tryCloneValue(StoreDataValue value)
        throws StandardException
    {
        if (value instanceof StoreValueOperations operations)
        {
            return operations.cloneValue(false);
        }
        StoreDataValue cloned = cloneValueThroughTypeSupport(value);
        if (cloned != null)
        {
            return cloned;
        }
        return cloneSqlValueReflectively(value);
    }

    private static StoreDataValue cloneHolderThroughTypeSupport(StoreDataValue value)
    {
        try
        {
            return StoreTypeUtil.cloneHolder(value);
        }
        catch (ClassCastException | IllegalArgumentException | IllegalStateException e)
        {
            return null;
        }
    }

    private static StoreDataValue cloneValueThroughTypeSupport(StoreDataValue value)
        throws StandardException
    {
        try
        {
            return StoreTypeUtil.cloneValue(value, false);
        }
        catch (ClassCastException | IllegalArgumentException | IllegalStateException e)
        {
            return null;
        }
    }

    private static boolean copyValueThroughTypeSupport(
            StoreDataValue destination,
            StoreDataValue source)
        throws StandardException
    {
        try
        {
            StoreTypeUtil.setValue(destination, source);
            return true;
        }
        catch (ClassCastException | IllegalArgumentException | IllegalStateException e)
        {
            return false;
        }
    }


    private static StoreDataValue storeDataValueReflectively(
            StoreDataValue value,
            String methodName)
    {
        try
        {
            Method method = value.getClass().getMethod(methodName);
            Object result = method.invoke(value);
            return result instanceof StoreDataValue storeDataValue ? storeDataValue : null;
        }
        catch (NoSuchMethodException e)
        {
            return null;
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException("Cannot access SQL value " + methodName
                    + " operation on " + value.getClass().getName(), e);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException)
            {
                throw runtimeException;
            }
            if (cause instanceof Error error)
            {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private static StoreDataValue cloneSqlValueReflectively(StoreDataValue value)
        throws StandardException
    {
        try
        {
            Method cloneValue = value.getClass().getMethod("cloneValue", boolean.class);
            Object cloned = cloneValue.invoke(value, false);
            if (cloned instanceof StoreDataValue storeDataValue)
            {
                return storeDataValue;
            }
            return null;
        }
        catch (NoSuchMethodException e)
        {
            return null;
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException("Cannot access SQL value clone operation on "
                    + value.getClass().getName(), e);
        }
        catch (InvocationTargetException e)
        {
            throw unwrapStandardException(e);
        }
    }

    private static boolean copySqlValueReflectively(
            StoreDataValue destination,
            StoreDataValue source)
        throws StandardException
    {
        Method setter = findSetValueMethod(destination.getClass(), source.getClass());
        if (setter == null)
        {
            return false;
        }
        try
        {
            setter.invoke(destination, source);
            return true;
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException("Cannot access SQL value set operation on "
                    + destination.getClass().getName(), e);
        }
        catch (InvocationTargetException e)
        {
            throw unwrapStandardException(e);
        }
    }

    private static Method findSetValueMethod(Class<?> destinationClass, Class<?> sourceClass)
    {
        for (Method method : destinationClass.getMethods())
        {
            if (!"setValue".equals(method.getName()) || method.getParameterCount() != 1)
            {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            if (!parameterType.isPrimitive() && parameterType.isAssignableFrom(sourceClass))
            {
                return method;
            }
        }
        return null;
    }

    private static Optional<Method> publicNoArgMethod(Class<?> type, String name)
    {
        if (type == null)
        {
            return Optional.empty();
        }
        for (Class<?> interfaceType : type.getInterfaces())
        {
            Optional<Method> method = publicNoArgMethod(interfaceType, name);
            if (method.isPresent())
            {
                return method;
            }
        }
        if (Modifier.isPublic(type.getModifiers()))
        {
            try
            {
                Method method = type.getMethod(name);
                if (method.getParameterCount() == 0
                        && Modifier.isPublic(method.getModifiers())
                        && Modifier.isPublic(method.getDeclaringClass().getModifiers()))
                {
                    return Optional.of(method);
                }
            }
            catch (NoSuchMethodException e)
            {
                // Keep searching public super types below.
            }
        }
        return publicNoArgMethod(type.getSuperclass(), name);
    }

    private static StandardException unwrapStandardException(InvocationTargetException e)
    {
        Throwable cause = e.getCause();
        if (cause instanceof StandardException standardException)
        {
            return standardException;
        }
        if (cause instanceof RuntimeException runtimeException)
        {
            throw runtimeException;
        }
        if (cause instanceof Error error)
        {
            throw error;
        }
        throw new IllegalStateException(cause);
    }
}
