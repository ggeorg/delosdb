/*

   Derby - Class org.apache.derby.iapi.services.cache.CacheFactory

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

package org.apache.derby.iapi.services.cache;

/**
	A factory for handing out caches.
*/
public interface CacheFactory {
	
	/**
		Create a cache that uses the class represented by holderClass as
		the holder class. This holderClass must implement Cacheable.

		@param holderFactory The factory for the objects that are to be cached.
		@param name			The name of the cache
		@param initialSize	The initial capacity of the cache
		@param maximumSize  The maximum number of objects the cache will hold
        @return a cache manager
	*/
	
	public CacheManager newCacheManager(CacheableFactory holderFactory, String name,
										int initialSize, int maximumSize);

	/**
		Create a cache whose entries, once their identity is set, may be kept
		and released without taking the entry's mutex. Suits a small cache of
		long-lived objects that many threads keep at once, such as the
		container cache. A factory without such a path creates an ordinary
		cache.

		@param holderFactory The factory for the objects that are to be cached.
		@param name			The name of the cache
		@param initialSize	The initial capacity of the cache
		@param maximumSize  The maximum number of objects the cache will hold
		@param lockFreeKeeps Whether entries may be kept without the mutex
        @return a cache manager
	*/
	default CacheManager newCacheManager(CacheableFactory holderFactory,
										 String name, int initialSize,
										 int maximumSize, boolean lockFreeKeeps)
	{
		return newCacheManager(holderFactory, name, initialSize, maximumSize);
	}
}

