package org.jetbrains.plugins.scala.lang.psi.api.statements

import com.intellij.psi.{PsiClass, PsiElement}
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScExistentialClause
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScDocCommentOwner, ScMember, ScTypeDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.{ScNamedElement, ScPolymorphicElement}

trait ScTypeAlias extends ScNamedElement
  // TODO: ScDefinitionWithAssignment should go to ScTypeAliasDefinition but first, we should fix parser
  //  to parse incomplete type definition as definition with error, not declaration:
  //  `type X =`
  //  it affects Enter handling after `type X =`
  with ScDefinitionWithAssignment
  with ScPolymorphicElement
  with ScMember
  with ScMember.WithBaseIconProvider
  with ScDocCommentOwner
  with ScCommentOwner {

  override protected def isSimilarMemberForNavigation(m: ScMember, isStrictCheck: Boolean): Boolean = m match {
    case t: ScTypeAlias => t.name == name
    case _ => false
  }

  def isExistentialTypeAlias: Boolean = {
    getContext match {
      case _: ScExistentialClause => true
      case _ => false
    }
  }

  def getTypeToken: PsiElement = findFirstChildByType(ScalaTokenTypes.kTYPE).get

  override def getOriginalElement: PsiElement = {
    val ccontainingClass = containingClass
    if (ccontainingClass == null) return this
    val originalClass: PsiClass = ccontainingClass.getOriginalElement.asInstanceOf[PsiClass]
    if (ccontainingClass eq  originalClass) return this
    if (!originalClass.isInstanceOf[ScTypeDefinition]) return this
    val c = originalClass.asInstanceOf[ScTypeDefinition]
    val aliasesIterator = c.aliases.iterator
    while (aliasesIterator.hasNext) {
      val alias = aliasesIterator.next()
      if (alias.name == name) return alias
    }
    this
  }

  def isDefinition: Boolean

  def physical: ScTypeAlias = this
}
