/*

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
package org.apache.derbyBuild;

import org.apache.derbyBuild.DelosRepositoryIntegrityModel.*;

import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;

import java.util.Arrays;
import java.util.List;

final class DelosRepositoryIntegrityMetrics extends TreeScanner<Void, Integer> {
    private final SourceFile source;
    private final CompilationUnitTree unit;
    private final SourcePositions positions;
    private final MethodRecord method;
    private final List<CatchRecord> catches;

    DelosRepositoryIntegrityMetrics(SourceFile source, CompilationUnitTree unit,
            SourcePositions positions, MethodRecord method,
            List<CatchRecord> catches) {
        this.source = source;
        this.unit = unit;
        this.positions = positions;
        this.method = method;
        this.catches = catches;
    }

    @Override
    public Void scan(Tree tree, Integer depth) {
        return tree == null ? null : super.scan(tree, depth == null ? 0 : depth);
    }

    @Override
    public Void visitIf(IfTree node, Integer depth) {
        method.branches++; method.statements++; nested(depth);
        return super.visitIf(node, depth + 1);
    }

    @Override
    public Void visitConditionalExpression(ConditionalExpressionTree node,
            Integer depth) {
        method.branches++; nested(depth);
        return super.visitConditionalExpression(node, depth + 1);
    }

    @Override
    public Void visitSwitch(SwitchTree node, Integer depth) {
        method.branches += Math.max(1, node.getCases().size());
        method.statements++; nested(depth);
        return super.visitSwitch(node, depth + 1);
    }

    @Override
    public Void visitSwitchExpression(SwitchExpressionTree node, Integer depth) {
        method.branches += Math.max(1, node.getCases().size());
        method.statements++; nested(depth);
        return super.visitSwitchExpression(node, depth + 1);
    }

    @Override
    public Void visitCase(CaseTree node, Integer depth) {
        nested(depth);
        return super.visitCase(node, depth + 1);
    }

    @Override
    public Void visitForLoop(ForLoopTree node, Integer depth) {
        method.loops++; method.statements++; nested(depth);
        return super.visitForLoop(node, depth + 1);
    }

    @Override
    public Void visitEnhancedForLoop(EnhancedForLoopTree node, Integer depth) {
        method.loops++; method.statements++; nested(depth);
        return super.visitEnhancedForLoop(node, depth + 1);
    }

    @Override
    public Void visitWhileLoop(WhileLoopTree node, Integer depth) {
        method.loops++; method.statements++; nested(depth);
        return super.visitWhileLoop(node, depth + 1);
    }

    @Override
    public Void visitDoWhileLoop(DoWhileLoopTree node, Integer depth) {
        method.loops++; method.statements++; nested(depth);
        return super.visitDoWhileLoop(node, depth + 1);
    }

    @Override
    public Void visitTry(TryTree node, Integer depth) {
        method.statements++; nested(depth);
        return super.visitTry(node, depth + 1);
    }

    @Override
    public Void visitCatch(CatchTree node, Integer depth) {
        method.catches++; nested(depth);
        String type = node.getParameter().getType().toString();
        boolean empty = node.getBlock().getStatements().isEmpty();
        boolean documented = empty && hasComment(node.getBlock());
        catches.add(new CatchRecord(source, method.owner, method.name,
                line(positions.getStartPosition(unit, node)), type,
                empty, documented, isGeneric(type)));
        return super.visitCatch(node, depth + 1);
    }

    @Override
    public Void visitExpressionStatement(ExpressionStatementTree node,
            Integer depth) {
        method.statements++;
        return super.visitExpressionStatement(node, depth);
    }

    @Override
    public Void visitVariable(VariableTree node, Integer depth) {
        method.statements++;
        return super.visitVariable(node, depth);
    }

    @Override
    public Void visitReturn(ReturnTree node, Integer depth) {
        method.statements++;
        return super.visitReturn(node, depth);
    }

    @Override
    public Void visitThrow(ThrowTree node, Integer depth) {
        method.statements++;
        return super.visitThrow(node, depth);
    }

    @Override
    public Void visitSynchronized(SynchronizedTree node, Integer depth) {
        method.statements++; nested(depth);
        return super.visitSynchronized(node, depth + 1);
    }

    private long line(long position) {
        return position < 0L ? 0L : unit.getLineMap().getLineNumber(position);
    }

    private void nested(Integer depth) {
        method.maxNesting = Math.max(method.maxNesting,
                depth == null ? 0 : depth);
    }

    private boolean hasComment(Tree tree) {
        long start = positions.getStartPosition(unit, tree);
        long end = positions.getEndPosition(unit, tree);
        if (start < 0L || end < start || end > source.text.length()) {
            return false;
        }
        String text = source.text.substring((int) start, (int) end);
        return text.contains("//") || text.contains("/*");
    }

    private static boolean isGeneric(String type) {
        return Arrays.stream(type.split("\\|"))
                .map(String::trim)
                .anyMatch(value -> value.equals("Exception")
                        || value.equals("java.lang.Exception")
                        || value.equals("Throwable")
                        || value.equals("java.lang.Throwable")
                        || value.equals("RuntimeException")
                        || value.equals("java.lang.RuntimeException"));
    }
}
