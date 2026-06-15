/*

   Derby - Class org.apache.derby.impl.services.reflect.ReflectMethod

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

package org.apache.derby.impl.services.reflect;

import org.apache.derby.iapi.services.loader.GeneratedMethod;

import org.apache.derby.shared.common.error.StandardException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

class ReflectMethod implements GeneratedMethod {

	private final Method realMethod;
	private final MethodHandle methodHandle;
	private final boolean voidReturn;

	ReflectMethod(Method m) {
		super();
		realMethod = m;
		voidReturn = m.getReturnType() == Void.TYPE;
		methodHandle = methodHandleFor(m, voidReturn);
	}

	public Object invoke(Object ref)
		throws StandardException {

		if (methodHandle != null) {
			return invokeWithMethodHandle(ref);
		}

		return invokeReflectively(ref);
	}

	private Object invokeWithMethodHandle(Object ref)
		throws StandardException {

		try {
			if (voidReturn) {
				methodHandle.invoke(ref);
				return null;
			}

			return methodHandle.invoke(ref);

		} catch (StandardException se) {
			throw se;
		} catch (Throwable t) {
			throw StandardException.unexpectedUserException(t);
		}
	}

	private Object invokeReflectively(Object ref)
		throws StandardException {

		Throwable t;

		try {
			return realMethod.invoke(ref);

		} catch (IllegalAccessException iae) {

			t = iae;

		} catch (IllegalArgumentException iae2) {

			t = iae2;

		} catch (InvocationTargetException ite) {

            t = ite;

		}
		
		throw StandardException.unexpectedUserException(t);
	}

	private static MethodHandle methodHandleFor(Method method, boolean voidReturn) {
		try {
			MethodType methodType = voidReturn
				? MethodType.methodType(void.class, Object.class)
				: MethodType.methodType(Object.class, Object.class);

			return MethodHandles.publicLookup()
				.unreflect(method)
				.asType(methodType);

		} catch (IllegalAccessException | RuntimeException e) {
			// Keep Derby's inherited reflective fallback if the generated class is
			// not accessible to a public MethodHandle lookup on a particular JVM or
			// module path. The fallback preserves the old behavior exactly.
			return null;
		}
	}
}
