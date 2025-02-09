package org.jetbrains.plugins.scala.lang.psi.impl.expr

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LazyParseablePsiElement
import com.intellij.psi.tree.ILazyParseableElementType
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.{ScalaElementVisitor, ScalaPsiElement}

import java.util

class ScBlockExprImpl(elementType: ILazyParseableElementType, buffer: CharSequence)
  extends LazyParseablePsiElement(elementType, buffer) with ScBlockExpr {

  override def toString: String = "BlockExpression"

  override def hasCaseClauses: Boolean = caseClauses.isDefined

  override protected def findChildrenByClassScala[T >: Null <: ScalaPsiElement](aClass: Class[T]): Array[T] = {
    val result = new util.ArrayList[T]
    var cur: PsiElement = getFirstChild
    while (cur != null) {
      if (aClass.isInstance(cur)) result.add(cur.asInstanceOf[T])
      cur = cur.getNextSibling
    }
    result.toArray[T](java.lang.reflect.Array.newInstance(aClass, result.size).asInstanceOf[Array[T]])
  }

  override protected def findChildByClassScala[T >: Null <: ScalaPsiElement](aClass: Class[T]): T = {
    var cur: PsiElement = getFirstChild
    while (cur != null) {
      if (aClass.isInstance(cur)) return cur.asInstanceOf[T]
      cur = cur.getNextSibling
    }
    null
  }

  override protected def acceptScala(visitor: ScalaElementVisitor): Unit = visitor.visitBlockExpression(this)
}
