/*
 * Copyright 2014 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.javascript.rhino.InputId;
import com.google.javascript.rhino.Node;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * <p>The syntactic scope creator scans the parse tree to create a Scope object
 * containing all the variable declarations in that scope. This class adds support
 * for block-level scopes introduced in ECMAScript 6.</p>
 *
 * <p>This implementation is not thread-safe.</p>
 *
 * @author moz@google.com (Michael Zhou)
 */
public class Es6SyntacticScopeCreator implements ScopeCreator {
  private final AbstractCompiler compiler;
  private final RedeclarationHandler redeclarationHandler;
  private final ScopeFactory scopeFactory;

  // The arguments variable is special, in that it's declared for every function,
  // but not explicitly declared.
  private static final String ARGUMENTS = "arguments";

  public static final RedeclarationHandler DEFAULT_REDECLARATION_HANDLER =
      new DefaultRedeclarationHandler();


  public Es6SyntacticScopeCreator(AbstractCompiler compiler) {
    this(compiler, DEFAULT_REDECLARATION_HANDLER);
  }

  public Es6SyntacticScopeCreator(AbstractCompiler compiler, ScopeFactory scopeFactory) {
    this(compiler, DEFAULT_REDECLARATION_HANDLER, scopeFactory);
  }

  Es6SyntacticScopeCreator(
      AbstractCompiler compiler, RedeclarationHandler redeclarationHandler) {
    this(compiler, redeclarationHandler, new DefaultScopeFactory());
  }

  Es6SyntacticScopeCreator(
      AbstractCompiler compiler, RedeclarationHandler redeclarationHandler,
      ScopeFactory scopeFactory) {
    this.compiler = compiler;
    this.redeclarationHandler = redeclarationHandler;
    this.scopeFactory = scopeFactory;
  }

  @Override
  public boolean hasBlockScope() {
    return true;
  }

  /** A simple API for injecting the use of alternative Scope classes */
  public interface ScopeFactory {
    Scope create(Scope parent, Node n);
  }

  private static class DefaultScopeFactory implements ScopeFactory {
    @Override
    public Scope create(Scope parent, Node n) {
      return (parent == null)
        ? Scope.createGlobalScope(n)
        : new Scope(parent, n);
    }
  }

  @Override
  public Scope createScope(Node n, Scope parent) {
    Scope scope = scopeFactory.create(parent, n);
    new ScopeScanner(compiler, redeclarationHandler, scope, null).populate();
    return scope;
  }

  /**
   * A class to traverse the AST looking for name definitions and add them to the Scope.
   */
  static class ScopeScanner {
    private final Scope scope;
    private final AbstractCompiler compiler;
    private final RedeclarationHandler redeclarationHandler;
    private InputId inputId;
    private final Set<Node> changeRootSet;

    ScopeScanner(AbstractCompiler compiler, Scope scope) {
      this(compiler, DEFAULT_REDECLARATION_HANDLER, scope, null);
    }

    ScopeScanner(
        AbstractCompiler compiler, RedeclarationHandler redeclarationHandler, Scope scope,
        Set<Node> changeRootSet) {
      this.compiler = compiler;
      this.redeclarationHandler = redeclarationHandler;
      this.scope = scope;
      this.changeRootSet = changeRootSet;
      checkState(changeRootSet == null || scope.isGlobal());
    }

    void populate() {
      Node n = scope.getRootNode();
      // If we are populating the global scope, inputId will be null, and need to be set
      // as we enter each SCRIPT node.
      inputId = NodeUtil.getInputId(n);
      switch (n.getToken()) {
        case FUNCTION: {
          // TODO(johnlenz): inputId maybe null if the FUNCTION node is detached
          // from the AST.
          // Is it meaningful to build a scope for detached FUNCTION node?

          final Node fnNameNode = n.getFirstChild();
          final Node args = fnNameNode.getNext();

          // Bleed the function name into the scope, if it hasn't
          // been declared in the outer scope.
          String fnName = fnNameNode.getString();
          if (!fnName.isEmpty() && NodeUtil.isFunctionExpression(n)) {
            declareVar(scope, fnNameNode);
          }

          // Args: Declare function variables
          checkState(args.isParamList());
          declareLHS(scope, args);
          // Since we create a separate scope for body, stop scanning here
          return;
        }

        case CLASS: {
          final Node classNameNode = n.getFirstChild();
          // Bleed the class name into the scope, if it hasn't
          // been declared in the outer scope.
          if (!classNameNode.isEmpty() && NodeUtil.isClassExpression(n)) {
            declareVar(scope, classNameNode);
          }
          return;
        }

        case ROOT:
        case SCRIPT:
          // n is the global scope
          checkState(scope.isGlobal(), scope);
          scanVars(n, scope, scope);
          return;

        case MODULE_BODY:
          scanVars(n, scope, scope);
          return;

        case FOR:
        case FOR_OF:
        case FOR_IN:
        case SWITCH:
          scanVars(n, null, scope);
          return;

        case BLOCK:
          if (NodeUtil.isFunctionBlock(n)) {
            scanVars(n, scope, scope);
          } else {
            scanVars(n, null, scope);
          }
          return;

        default:
          throw new RuntimeException("Illegal scope root: " + n);
      }
    }

    private void declareLHS(Scope s, Node n) {
      for (Node lhs : NodeUtil.getLhsNodesOfDeclaration(n)) {
        declareVar(s, lhs);
      }
    }

    /**
     * Scans and gather variables declarations under a Node
     *
     * @param n The node
     * @param hoistScope The scope that is the hoist target for vars, if we are scanning for vars.
     * @param blockScope The scope that is the hoist target for block-level declarations, if we are
     *     scanning for block level declarations.
     */
    private void scanVars(Node n, @Nullable Scope hoistScope, @Nullable Scope blockScope) {
      switch (n.getToken()) {
        case VAR:
          if (hoistScope != null) {
            declareLHS(hoistScope, n);
          }
          return;

        case LET:
        case CONST:
          // Only declare when scope is the current lexical scope
          if (blockScope != null) {
            declareLHS(blockScope, n);
          }
          return;

        case IMPORT:
          declareLHS(hoistScope, n);
          return;

        case FUNCTION:
          if (NodeUtil.isFunctionExpression(n) || blockScope == null) {
            return;
          }

          String fnName = n.getFirstChild().getString();
          if (fnName.isEmpty()) {
            // This is invalid, but allow it so the checks can catch it.
            return;
          }
          declareVar(blockScope, n.getFirstChild());
          return;   // should not examine function's children

        case CLASS:
          if (NodeUtil.isClassExpression(n) || blockScope == null) {
            return;
          }
          String className = n.getFirstChild().getString();
          if (className.isEmpty()) {
            // This is invalid, but allow it so the checks can catch it.
            return;
          }
          declareVar(blockScope, n.getFirstChild());
          return;  // should not examine class's children

        case CATCH:
          checkState(n.hasTwoChildren(), n);
          // the first child is the catch var and the second child
          // is the code block
          if (blockScope != null) {
            declareLHS(blockScope, n);
          }
          // A new scope is not created for this BLOCK because there is a scope
          // created for the BLOCK above the CATCH
          final Node block = n.getSecondChild();
          scanVars(block, hoistScope, blockScope);
          return; // only one child to scan

        case SCRIPT:
          if (changeRootSet != null && !changeRootSet.contains(n)) {
            // If there is a changeRootSet configured, that means
            // a partial update is being done and we should skip
            // any SCRIPT that aren't being asked for.
            return;
          }
          inputId = n.getInputId();
          checkNotNull(inputId);
          break;

        case MODULE_BODY:
          // Module bodies are not part of global scope.
          if (hoistScope.isGlobal()) {
            return;
          }
          break;

        default:
          break;
      }

      boolean isBlockStart = blockScope != null && n == blockScope.getRootNode();
      boolean enteringNewBlock = !isBlockStart && NodeUtil.createsBlockScope(n);
      if (enteringNewBlock && hoistScope == null) {
        // We only enter new blocks when scanning for hoisted vars
        return;
      }

      // Variables can only occur in statement-level nodes, so
      // we only need to traverse children in a couple special cases.
      if (NodeUtil.isControlStructure(n) || NodeUtil.isStatementBlock(n)) {
        for (Node child = n.getFirstChild(); child != null;) {
          Node next = child.getNext();
          scanVars(child, hoistScope, enteringNewBlock ? null : blockScope);
          child = next;
        }
      }
    }

    /**
     * Declares a variable.
     *
     * @param s The scope to declare the variable in.
     * @param n The node corresponding to the variable name.
     */
    private void declareVar(Scope s, Node n) {
      checkState(n.isName() || n.isStringKey() || n.isImportStar(),
          "Invalid node for declareVar: %s", n);

      String name = n.getString();
      // Because of how we scan the variables, it is possible to encounter
      // the same var declared name node twice. Bail out in this case.
      // TODO(johnlenz): hash lookups are not free and
      // building scopes are already expensive
      // restructure the scope building to avoid this check.
      Var v = s.getOwnSlot(name);
      if (v != null && v.getNode() == n) {
        return;
      }

      CompilerInput input = compiler.getInput(inputId);
      if (v != null
          || isShadowingDisallowed(name, s)
          || ((s.isFunctionScope()
              || s.isFunctionBlockScope()) && name.equals(ARGUMENTS))) {
        redeclarationHandler.onRedeclaration(s, name, n, input);
      } else {
        s.declare(name, n, input);
      }
    }

    // Function body declarations are not allowed to shadow
    // function parameters.
    private static boolean isShadowingDisallowed(String name, Scope s) {
      if (s.isFunctionBlockScope()) {
        Var maybeParam = s.getParent().getOwnSlot(name);
        return maybeParam != null && maybeParam.isParam();
      }
      return false;
    }
  }

  /**
   * Interface for injectable duplicate handling.
   */
  interface RedeclarationHandler {
    void onRedeclaration(
        Scope s, String name, Node n, CompilerInput input);
  }

  /**
   * The default handler for duplicate declarations.
   */
  static class DefaultRedeclarationHandler implements RedeclarationHandler {
    @Override
    public void onRedeclaration(Scope s, String name, Node n, CompilerInput input) {}
  }
}
