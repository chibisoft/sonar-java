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

import com.sonar.sslr.api.AstNode;
import com.sonar.sslr.squid.checks.SquidCheck;
import org.sonar.check.Priority;
import org.sonar.check.Rule;
import org.sonar.java.ast.parser.JavaGrammar;
import org.sonar.sslr.parser.LexerlessGrammar;

@Rule(
  key = "S1156",
  priority = Priority.MAJOR)
public class EmptyStringConcatenationCheck extends SquidCheck<LexerlessGrammar> {

  private static boolean inAnnotation;

  @Override
  public void init() {
    subscribeTo(JavaGrammar.ANNOTATION);
    subscribeTo(JavaGrammar.ADDITIVE_EXPRESSION);
  }

  @Override
  public void visitFile(AstNode node) {
    inAnnotation = false;
  }

  @Override
  public void visitNode(AstNode node) {
    if (node.is(JavaGrammar.ANNOTATION)) {
      inAnnotation = true;
    } else if (!inAnnotation) {
      for (AstNode primary : node.getChildren(JavaGrammar.PRIMARY)) {
        if (isEmptyString(primary)) {
          getContext().createLineViolation(this, "Replace this empty String concatenation by a method such as String.valueOf() or Object.toString().", primary);
        }
      }
    }
  }

  @Override
  public void leaveNode(AstNode node) {
    if (node.is(JavaGrammar.ANNOTATION)) {
      inAnnotation = false;
    }
  }

  private static boolean isEmptyString(AstNode node) {
    return node.getToken().equals(node.getLastToken()) &&
      "\"\"".equals(node.getTokenOriginalValue());
  }

}
