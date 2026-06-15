/*

   Derby - Class org.apache.derby.impl.services.monitor.ProtocolKey

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

package org.apache.derby.impl.services.monitor;

import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.iapi.services.monitor.Monitor;
import java.util.Objects;


/**
	A class that represents a key for a module search.
*/


class ProtocolKey {

	/*
	** Fields.
	*/

	/**
		The class of the factory
	*/
	private final Class<?> factoryInterface;

	/**
		Name of module, can be null.
	*/
	private final String identifier;

	/*
	** Constructor
	*/

	protected ProtocolKey(Class<?> factoryInterface, String identifier)
	{
		super();
		this.factoryInterface = factoryInterface;
		this.identifier = identifier;
	}

	static ProtocolKey create(String className, String identifier) throws StandardException {

		Throwable t;
		try {
			return new ProtocolKey(Class.forName(className), identifier);

		} catch (ClassNotFoundException cnfe) {
			t = cnfe;
		} catch (IllegalArgumentException iae) {
			t = iae;
        } catch (LinkageError le) {
            t = le;
		}

		throw Monitor.exceptionStartingModule(t);	
	}

	/*
	** Methods required to use this key
	*/

	protected Class<?> getFactoryInterface() {
		return factoryInterface;
	}

	protected String getIdentifier() {
		return identifier;
	}

	/*
	**
	*/

	public int hashCode() {
		return factoryInterface.hashCode() +
			(identifier == null ? 0  : identifier.hashCode());
	}

	public boolean equals(Object other) {
		if (!(other instanceof ProtocolKey)) {
			return false;
		}

		ProtocolKey otherKey = (ProtocolKey) other;
		return factoryInterface == otherKey.factoryInterface
			&& Objects.equals(identifier, otherKey.identifier);
	}

	public String toString() {

		return factoryInterface.getName() + " (" + identifier + ")";
	}
}
