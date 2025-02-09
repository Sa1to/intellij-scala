package org.jetbrains.plugins.scala.lang.surroundWith.surrounders.expression

import com.intellij.lang.ASTNode
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/*
 * ScalaWithBracketsSurrounder is responsible of surrounders, witch enclause expression in brackets: { Expression } or ( Expression )
 */
class ScalaWithBracesSurrounder extends ScalaExpressionSurrounder {

  override def getTemplateAsString(elements: Array[PsiElement]): String = "{" + super.getTemplateAsString(elements) + "}"

  //noinspection ScalaExtractStringToBundle
  override def getTemplateDescription = "{  }"

  override def getSurroundSelectionRange(editor: Editor, expr: ASTNode): TextRange = {
    val offset = expr.getTextRange.getEndOffset
    new TextRange(offset, offset)
  }

  override def needParenthesis(element: PsiElement) = false
}
