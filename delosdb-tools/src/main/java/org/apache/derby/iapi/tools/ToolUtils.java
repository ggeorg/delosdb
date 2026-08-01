/*

   Derby - Class org.apache.derby.iapi.tools.ToolUtils

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

package org.apache.derby.iapi.tools;

import org.apache.derby.shared.common.util.ArrayUtil;

public abstract class ToolUtils {

    /** Copy an array of objects; the original array could be null. */
    public static Object[] copy(Object[] original) {
        return ArrayUtil.copy(original);
    }

    /** Copy a possibly null array of strings. */
    public static String[] copy(String[] original) {
        return ArrayUtil.copy(original);
    }

    /** Copy a possibly null array of booleans. */
    public static boolean[] copy(boolean[] original) {
        return ArrayUtil.copy(original);
    }

    /** Copy a possibly null array of bytes. */
    public static byte[] copy(byte[] original) {
        return ArrayUtil.copy(original);
    }

    /** Copy a possibly null array of ints. */
    public static int[] copy(int[] original) {
        return ArrayUtil.copy(original);
    }

    /** Copy a possibly null two-dimensional array of ints. */
    public static int[][] copy2(int[][] original) {
        return ArrayUtil.copy2(original);
    }
}
