/*

   Derby - Class org.apache.derby.impl.services.reflect.ReflectGeneratedClass

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
import org.apache.derby.iapi.services.loader.GeneratedByteCode;
import org.apache.derby.iapi.services.loader.ClassFactory;

import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ReflectGeneratedClass extends LoadedGeneratedClass {

	private static final Class<?>[] NO_PARAMETER_TYPES = new Class<?>[0];
	private static final GeneratedMethod[] directs;

	private final ConcurrentMap<String,GeneratedMethod> methodCache;

	static {
		directs = new GeneratedMethod[10];
		for (int i = 0; i < directs.length; i++) {
			directs[i] = new DirectCall(i);
		}
	}

	public ReflectGeneratedClass(ClassFactory cf, Class jvmClass) {
		super(cf, jvmClass);
		methodCache = new ConcurrentHashMap<String,GeneratedMethod>();
	}

	public GeneratedMethod getMethod(String simpleName)
		throws StandardException {

		GeneratedMethod cached = methodCache.get(simpleName);
		if (cached != null)
			return cached;

		GeneratedMethod resolved = resolveMethod(simpleName);
		GeneratedMethod previous = methodCache.putIfAbsent(simpleName, resolved);
		return previous == null ? resolved : previous;
	}

	private GeneratedMethod resolveMethod(String simpleName)
		throws StandardException {

		// Derby-generated activation methods e0..e9 have a hot direct-call path.
		// Keep that path explicit: it avoids reflective dispatch for the common
		// generated-method names and makes invalid names such as "ex" fall through
		// to the normal no-such-method diagnostic instead of an array bounds error.
		if (isDirectCallName(simpleName)) {
			return directs[simpleName.charAt(1) - '0'];
		}

		// Only look for public methods that take no arguments.
		try {
			Method m = getJVMClass().getMethod(simpleName, NO_PARAMETER_TYPES);
			return new ReflectMethod(m);

		} catch (NoSuchMethodException nsme) {
			throw StandardException.newException(SQLState.GENERATED_CLASS_NO_SUCH_METHOD,
				nsme, getName(), simpleName);
		}
	}

	private static boolean isDirectCallName(String simpleName) {
		return simpleName.length() == 2
			&& simpleName.charAt(0) == 'e'
			&& simpleName.charAt(1) >= '0'
			&& simpleName.charAt(1) <= '9';
	}
}

class DirectCall implements GeneratedMethod {

	private final int which;

	DirectCall(int which) {

		this.which = which;
	}

	public Object invoke(Object ref)
		throws StandardException {

		try {

			GeneratedByteCode gref = (GeneratedByteCode) ref;
			switch (which) {
			case 0:
				return gref.e0();
			case 1:
				return gref.e1();
			case 2:
				return gref.e2();
			case 3:
				return gref.e3();
			case 4:
				return gref.e4();
			case 5:
				return gref.e5();
			case 6:
				return gref.e6();
			case 7:
				return gref.e7();
			case 8:
				return gref.e8();
			case 9:
				return gref.e9();
			}
			return null;
		} catch (StandardException se) {
			throw se;
		}		
		catch (Throwable t) {
			throw StandardException.unexpectedUserException(t);
		}
	}
}
