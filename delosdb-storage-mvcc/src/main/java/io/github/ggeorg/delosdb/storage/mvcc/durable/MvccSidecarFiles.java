/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.durable.MvccSidecarFiles

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
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.CRC32;

/** Shared file helpers for small MVCC sidecar files with checksum trailers. */
final class MvccSidecarFiles {
    private static final String REWRITE_SUFFIX = ".rewrite";

    private MvccSidecarFiles() {
    }

    static int checksum(byte[] bytes, int offset, int length) {
        CRC32 checksum = new CRC32();
        checksum.update(bytes, offset, length);
        return (int) checksum.getValue();
    }

    static void rewriteAtomically(
            Path path,
            byte[] bytes,
            MvccSidecarFlushPolicy flushPolicy) throws IOException {
        MvccDurableFiles.rewriteAtomically(path, bytes, REWRITE_SUFFIX, flushPolicy);
    }

    static void deleteWithRewriteSibling(
            Path path,
            MvccSidecarFlushPolicy flushPolicy) throws IOException {
        MvccDurableFiles.deleteWithTemporarySibling(path, REWRITE_SUFFIX, flushPolicy);
    }
}
