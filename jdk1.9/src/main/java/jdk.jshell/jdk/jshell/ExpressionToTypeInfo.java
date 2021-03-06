/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package jdk.jshell;

import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import jdk.jshell.TaskFactory.AnalyzeTask;

/**
 * Compute information about an expression string, particularly its type name.
 */
class ExpressionToTypeInfo {

    private static final String OBJECT_TYPE_NAME = "Object";

    final AnalyzeTask at;
    final CompilationUnitTree cu;
    final JShell state;
    final Symtab syms;
    final Types types;

    private ExpressionToTypeInfo(AnalyzeTask at, CompilationUnitTree cu, JShell state) {
        this.at = at;
        this.cu = cu;
        this.state = state;
        this.syms = Symtab.instance(at.context);
        this.types = Types.instance(at.context);
    }

    public static class ExpressionInfo {
        ExpressionTree tree;
        String typeName;
        boolean isNonVoid;
    }

    // return mechanism and other general structure from TreePath.getPath()
    private static class Result extends Error {

        static final long serialVersionUID = -5942088234594905629L;
        final TreePath expressionPath;

        Result(TreePath path) {
            this.expressionPath = path;
        }
    }

    private static class PathFinder extends TreePathScanner<TreePath, Boolean> {

        // Optimize out imports etc
        @Override
        public TreePath visitCompilationUnit(CompilationUnitTree node, Boolean isTargetContext) {
            return scan(node.getTypeDecls(), isTargetContext);
        }

        // Only care about members
        @Override
        public TreePath visitClass(ClassTree node, Boolean isTargetContext) {
            return scan(node.getMembers(), isTargetContext);
        }

        // Only want the doit method where the code is
        @Override
        public TreePath visitMethod(MethodTree node, Boolean isTargetContext) {
            if (Util.isDoIt(node.getName())) {
                return scan(node.getBody(), true);
            } else {
                return null;
            }
        }

        @Override
        public TreePath visitReturn(ReturnTree node, Boolean isTargetContext) {
            ExpressionTree tree = node.getExpression();
            TreePath tp = new TreePath(getCurrentPath(), tree);
            if (isTargetContext) {
                throw new Result(tp);
            } else {
                return null;
            }
        }
    }

    private Type pathToType(TreePath tp) {
        return (Type) at.trees().getTypeMirror(tp);
    }

    private Type pathToType(TreePath tp, Tree tree) {
        if (tree instanceof ConditionalExpressionTree) {
            // Conditionals always wind up as Object -- this corrects
            ConditionalExpressionTree cet = (ConditionalExpressionTree) tree;
            Type tmt = pathToType(new TreePath(tp, cet.getTrueExpression()));
            Type tmf = pathToType(new TreePath(tp, cet.getFalseExpression()));
            if (!tmt.isPrimitive() && !tmf.isPrimitive()) {
                Type lub = types.lub(tmt, tmf);
                // System.err.printf("cond ? %s : %s  --  lub = %s\n",
                //             varTypeName(tmt), varTypeName(tmf), varTypeName(lub));
                return lub;
            }
        }
        return pathToType(tp);
    }

    /**
     * Entry method: get expression info
     * @param code the expression as a string
     * @param state a JShell instance
     * @return type information
     */
    public static ExpressionInfo expressionInfo(String code, JShell state) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        try {
            OuterWrap codeWrap = state.outerMap.wrapInTrialClass(Wrap.methodReturnWrap(code));
            AnalyzeTask at = state.taskFactory.new AnalyzeTask(codeWrap);
            CompilationUnitTree cu = at.firstCuTree();
            if (at.hasErrors() || cu == null) {
                return null;
            }
            return new ExpressionToTypeInfo(at, cu, state).typeOfExpression();
        } catch (Exception ex) {
            return null;
        }
    }

    private ExpressionInfo typeOfExpression() {
        return treeToInfo(findExpressionPath());
    }

    private TreePath findExpressionPath() {
        try {
            new PathFinder().scan(new TreePath(cu), false);
        } catch (Result result) {
            return result.expressionPath;
        }
        return null;
    }

    private ExpressionInfo treeToInfo(TreePath tp) {
        if (tp != null) {
            Tree tree = tp.getLeaf();
            if (tree instanceof ExpressionTree) {
                ExpressionInfo ei = new ExpressionInfo();
                ei.tree = (ExpressionTree) tree;
                Type type = pathToType(tp, tree);
                if (type != null) {
                    switch (type.getKind()) {
                        case VOID:
                        case NONE:
                        case ERROR:
                        case OTHER:
                            break;
                        case NULL:
                            ei.isNonVoid = true;
                            ei.typeName = OBJECT_TYPE_NAME;
                            break;
                        default: {
                            ei.isNonVoid = true;
                            ei.typeName = varTypeName(type);
                            if (ei.typeName == null) {
                                ei.typeName = OBJECT_TYPE_NAME;
                            }
                            break;
                        }
                    }
                }
                return ei;
            }
        }
        return null;
    }

    private String varTypeName(Type type) {
        try {
            TypePrinter tp = new VarTypePrinter(at.messages(),
                    state.maps::fullClassNameAndPackageToClass, syms, types);
            return tp.toString(type);
        } catch (Exception ex) {
            return null;
        }
    }

}
