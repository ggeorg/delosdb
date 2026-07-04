/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.durable.MvccSidecarFlushPolicy

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

package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Objects;

/** Flush policy seam for durable MVCC sidecar stores and append logs. */
@FunctionalInterface
interface MvccSidecarFlushPolicy {
    MvccSidecarFlushPolicy IMMEDIATE = (channel, path) -> channel.force(true);

    void force(FileChannel channel, Path path) throws IOException;

    static MvccSidecarFlushPolicy immediate() {
        return IMMEDIATE;
    }

    static MvccSidecarFlushPolicy require(MvccSidecarFlushPolicy policy) {
        return Objects.requireNonNull(policy, "flushPolicy");
    }
}
