/*
 * SonarQube Java
 * Copyright (C) 2012 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.java.checks;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.check.Priority;
import org.sonar.check.Rule;
import org.sonar.java.model.JavaTree;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.ClassTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.Modifier;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;
import org.sonar.squidbridge.annotations.ActivatedByDefault;
import org.sonar.squidbridge.annotations.SqaleConstantRemediation;
import org.sonar.squidbridge.annotations.SqaleSubCharacteristic;

import javax.annotation.Nullable;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

@Rule(
  key = "HiddenFieldCheck",
  name = "Local variables should not shadow class fields",
  tags = {"pitfall"},
  priority = Priority.MAJOR)
@ActivatedByDefault
@SqaleSubCharacteristic(RulesDefinition.SubCharacteristics.DATA_RELIABILITY)
@SqaleConstantRemediation("5min")
public class HiddenFieldCheck extends SubscriptionBaseVisitor {

  private final Deque<ImmutableMap<String, VariableTree>> fields = Lists.newLinkedList();
  private final Deque<List<VariableTree>> excludedVariables = Lists.newLinkedList();
  private final List<VariableTree> flattenExcludedVariables = Lists.newArrayList();

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return ImmutableList.of(
        Tree.Kind.CLASS,
        Tree.Kind.ENUM,
        Tree.Kind.INTERFACE,
        Tree.Kind.ANNOTATION_TYPE,
        Tree.Kind.VARIABLE,
        Tree.Kind.METHOD,
        Tree.Kind.CONSTRUCTOR,
        Tree.Kind.STATIC_INITIALIZER
    );
  }

  @Override
  public void scanFile(JavaFileScannerContext context) {
    fields.clear();
    excludedVariables.clear();
    flattenExcludedVariables.clear();
    super.scanFile(context);
  }

  @Override
  public void visitNode(Tree tree) {
    if (isClassTree(tree)) {
      ClassTree classTree = (ClassTree) tree;
      ImmutableMap.Builder<String, VariableTree> builder = ImmutableMap.builder();
      for (Tree member : classTree.members()) {
        if (member.is(Tree.Kind.VARIABLE)) {
          VariableTree variableTree = (VariableTree) member;
          builder.put(variableTree.simpleName().name(), variableTree);
        }
      }
      fields.push(builder.build());
      excludedVariables.push(Lists.<VariableTree>newArrayList());
    } else if (tree.is(Tree.Kind.VARIABLE)) {
      VariableTree variableTree = (VariableTree) tree;
      isVariableHidingField(variableTree);
    } else if (tree.is(Tree.Kind.STATIC_INITIALIZER)) {
      excludeVariablesFromBlock((BlockTree) tree);
    } else {
      MethodTree methodTree = (MethodTree) tree;
      excludedVariables.peek().addAll(methodTree.parameters());
      flattenExcludedVariables.addAll(methodTree.parameters());
      if (methodTree.modifiers().modifiers().contains(Modifier.STATIC)) {
        excludeVariablesFromBlock(methodTree.block());
      }
    }
  }

  private void isVariableHidingField(VariableTree variableTree) {
    for (ImmutableMap<String, VariableTree> variables : fields) {
      if (variables.values().contains(variableTree)) {
        return;
      }
      String identifier = variableTree.simpleName().name();
      VariableTree hiddenVariable = variables.get(identifier);
      if (!flattenExcludedVariables.contains(variableTree) && hiddenVariable != null) {
        addIssue(variableTree, "Rename \"" + identifier + "\" which hides the field declared at line " + ((JavaTree) hiddenVariable).getLine() + ".");
        return;
      }
    }
  }

  private boolean isClassTree(Tree tree) {
    return tree.is(Tree.Kind.CLASS) || tree.is(Tree.Kind.ENUM) || tree.is(Tree.Kind.INTERFACE) || tree.is(Tree.Kind.ANNOTATION_TYPE);
  }

  @Override
  public void leaveNode(Tree tree) {
    if (isClassTree(tree)) {
      fields.pop();
      flattenExcludedVariables.removeAll(excludedVariables.pop());
    }
  }

  private void excludeVariablesFromBlock(@Nullable BlockTree blockTree) {
    if (blockTree != null) {
      List<VariableTree> variableTrees = new VariableList().scan(blockTree);
      excludedVariables.peek().addAll(variableTrees);
      flattenExcludedVariables.addAll(variableTrees);
    }
  }

  private static class VariableList {

    private List<VariableTree> variables;
    private List<Tree.Kind> visitNodes;
    private List<Tree.Kind> excludedNodes;

    List<VariableTree> scan(Tree tree) {
      visitNodes = nodesToVisit();
      excludedNodes = excludedNodes();
      variables = Lists.newArrayList();
      visit(tree);
      return variables;
    }

    public List<Tree.Kind> nodesToVisit() {
      return ImmutableList.of(Tree.Kind.VARIABLE);
    }

    public List<Tree.Kind> excludedNodes() {
      return ImmutableList.of(Tree.Kind.METHOD, Tree.Kind.CLASS, Tree.Kind.ENUM, Tree.Kind.INTERFACE, Tree.Kind.NEW_CLASS);
    }

    private void visit(Tree tree) {
      if (isSubscribed(tree)) {
        variables.add((VariableTree) tree);
      }
      visitChildren(tree);
    }

    private void visitChildren(Tree tree) {
      JavaTree javaTree = (JavaTree) tree;
      if (!javaTree.isLeaf()) {
        for (Iterator<Tree> iter = javaTree.childrenIterator(); iter.hasNext(); ) {
          Tree next = iter.next();
          if (next != null && !isExcluded(next)) {
            visit(next);
          }
        }
      }
    }

    private boolean isSubscribed(Tree tree) {
      return visitNodes.contains(((JavaTree) tree).getKind());
    }

    private boolean isExcluded(Tree tree) {
      return excludedNodes.contains(((JavaTree) tree).getKind());
    }
  }

}
