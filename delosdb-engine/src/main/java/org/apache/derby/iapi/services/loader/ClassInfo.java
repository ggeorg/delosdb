/*

   Derby - Class org.apache.derby.iapi.services.loader.ClassInfo

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

package org.apache.derby.iapi.services.loader;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class ClassInfo implements InstanceGetter {

	private final Class<?> clazz;
	private Constructor<?> noArgConstructor;

	public ClassInfo(Class<?> clazz) {
		this.clazz = clazz;
	}

	/**
		Return the name of this class.

        @return the name of this class
	*/
	public final String getClassName() {
		return clazz.getName();
	}

	/**
		Return the class object for this class.

        @return the class object for this class

	*/
	public final Class getClassObject() {

		return clazz;
	}

	/**
		Create an instance of this class. Assumes that clazz has already been
		initialized. Cache the public no-arg constructor directly instead of
		repeating reflective constructor lookup for every registered-format object.

		@exception InstantiationException Zero arg constructor can not be executed
		@exception IllegalAccessException Class or zero arg constructor is not public.
		@exception InvocationTargetException Exception thrown in zero-arg constructor.
        @exception NoSuchMethodException Missing public zero-arg constructor.

	*/
	public Object getNewInstance()
		throws InstantiationException,
               IllegalAccessException,
               InvocationTargetException,
               NoSuchMethodException
  {

		Constructor<?> constructor = noArgConstructor;
		if (constructor == null) {
			constructor = clazz.getConstructor();
			noArgConstructor = constructor;
		}

		return constructor.newInstance();
	}
}
