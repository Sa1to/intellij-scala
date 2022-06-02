package org.jetbrains.plugins.scala.codeInspection.shadow

import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.codeInspection.ex.DisableInspectionToolAction
import com.intellij.codeInspection.ui.InspectionOptionsPanel
import com.intellij.codeInspection.{InspectionManager, LocalQuickFix, ProblemDescriptor, ProblemHighlightType}
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.annotations.Nls
import org.jetbrains.plugins.scala.codeInspection.quickfix.RenameElementQuickfix
import org.jetbrains.plugins.scala.codeInspection.ui.CompilerInspectionOptions._
import org.jetbrains.plugins.scala.codeInspection.{AbstractRegisteredInspection, ScalaInspectionBundle}
import org.jetbrains.plugins.scala.lang.lexer.ScalaModifier
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScVariable
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScClassParameter
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScMember, ScTypeDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.{ScModifierListOwner, ScNamedElement}
import org.jetbrains.plugins.scala.util.EnumSet.EnumSetOps

import java.awt.event.ItemEvent
import javax.swing.JComponent
import scala.beans.BooleanBeanProperty

final class PrivateShadowInspection extends AbstractRegisteredInspection {
  import PrivateShadowInspection._

  override protected def problemDescriptor(element:             PsiElement,
                                           maybeQuickFix:       Option[LocalQuickFix],
                                           descriptionTemplate: String,
                                           highlightType:       ProblemHighlightType)
                                          (implicit manager: InspectionManager, isOnTheFly: Boolean): Option[ProblemDescriptor] =
    element match {
      case elem: ScNamedElement if isElementShadowing(elem) => Some(createProblemDescriptor(elem, annotationDescription))
      case _ => None
    }

  private lazy val disableInspectionToolAction = new DisableInspectionToolAction(this) with LowPriorityAction

  private def createProblemDescriptor(elem: ScNamedElement, @Nls description: String)
                                     (implicit manager: InspectionManager, isOnTheFly: Boolean): ProblemDescriptor = {
    val showAsError =
      fatalWarningsCompilerOption &&
        (isCompilerOptionPresent(elem, "-Xfatal-warnings") || isCompilerOptionPresent(elem, "-Werror"))

    val range = new TextRange(0, if (elem.getText.contains(":")) elem.getText.indexOf(":") else elem.getText.length)

    manager.createProblemDescriptor(
      elem,
      range,
      description,
      if (showAsError) ProblemHighlightType.ERROR else ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
      isOnTheFly,
      new RenameElementQuickfix(elem, renameQuickFixDescription), disableInspectionToolAction
    )
  }

  private def isElementShadowing(elem: ScNamedElement): Boolean =
    elem.nameContext match {
      case e: ScModifierListOwner if e.getModifierList.modifiers.contains(ScalaModifier.Override) =>
        false
      case _: ScClassParameter =>
        findTypeDefinition(elem) match {
          case Some(typeDefinition) if isElementShadowing(elem, typeDefinition) => true
          case _  => false
        }
      case _ =>
        false
    }

  private def isElementShadowing(elem: ScNamedElement, typeDefinition: ScTypeDefinition) : Boolean = {
    // Fields suspected of being shadowed are all fields belonging to the containing class or trait with the same name
    // as the element under inspection, but not itself, and for which we can get the name context implementing ScMember,
    // so we can later check its modifiers.
    val suspects =
      typeDefinition
        .allTermsByName(elem.name)
        .collect { case term: ScNamedElement if !term.isEquivalentTo(elem) => term.nameContext }
        .collect { case nameContext: ScMember => nameContext }

    if (suspects.isEmpty)
      false
    else
      elem.nameContext match {
        case _ if isInspectionAllowed(elem, privateShadowCompilerOption, "-Xlint:private-shadow") =>
          lazy val isUsed = {
            val scope = new LocalSearchScope(typeDefinition)
            ReferencesSearch.search(elem, scope).findFirst() != null
          }

          suspects.exists {
            case s: ScVariable if !s.isPrivate => isUsed
            case s: ScClassParameter if s.isVar && !s.isPrivate => isUsed
            case _ => false
          }
         case _ =>
          false
      }
  }

  @BooleanBeanProperty
  var privateShadowCompilerOption: Boolean = true

  @BooleanBeanProperty
  var fatalWarningsCompilerOption: Boolean = true

  @Override
  override def createOptionsPanel(): JComponent = {
    val panel = new InspectionOptionsPanel(this)
    val compilerOptionCheckbox = panel.addCheckboxEx(
      ScalaInspectionBundle.message("private.shadow.compiler.option.label"),
      "privateShadowCompilerOption"
    )
    val fatalWarningsCheckbox = panel.addDependentCheckBox(
      ScalaInspectionBundle.message("private.shadow.fatal.warnings.label"),
      "fatalWarningsCompilerOption",
      compilerOptionCheckbox
    )
    compilerOptionCheckbox.addItemListener { e =>
      if (e.getStateChange == ItemEvent.DESELECTED) fatalWarningsCheckbox.setSelected(false)
    }
    panel
  }
}

object PrivateShadowInspection {
  @Nls
  val annotationDescription: String = ScalaInspectionBundle.message("private.shadow.description")

  @Nls
  private val renameQuickFixDescription: String = ScalaInspectionBundle.message("private.shadow.rename.identifier")

  private def findTypeDefinition(elem: PsiElement): Option[ScTypeDefinition] =
    Option(PsiTreeUtil.getParentOfType(elem, classOf[ScTypeDefinition]))
}
