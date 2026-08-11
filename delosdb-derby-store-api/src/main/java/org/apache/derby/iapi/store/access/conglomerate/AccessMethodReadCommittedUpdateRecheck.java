/*

   Derby - Class org.apache.derby.iapi.store.access.conglomerate.AccessMethodReadCommittedUpdateRecheck

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.access.conglomerate;

/** Optional access-method hook for READ COMMITTED wait-and-recheck updates. */
public interface AccessMethodReadCommittedUpdateRecheck {
    void enableReadCommittedUpdateRecheck();
}
