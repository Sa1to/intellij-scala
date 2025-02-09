package org.jetbrains.plugins.scala.codeInsight.intention.types

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.plugins.scala.ScalaBundle
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiManager

/**
 * Converts expression representing scala collection to
 * java equivalent using [[scala.collection.JavaConverters]] before Scala 2.13
 * and [[scala.jdk.CollectionConverters]] since Scala 2.13
 */
class ConvertScalaToJavaCollectionIntention extends BaseJavaConvertersIntention("asJava") {

  override def targetCollections(project: Project, scope: GlobalSearchScope): Set[PsiClass] = {
    val manager = ScalaPsiManager.instance(project)
    Set(
      "scala.collection.Seq",
      "scala.collection.Set",
      "scala.collection.Map",
      "scala.collection.Iterator",
      "scala.collection.Iterable"
    ).flatMap(fqn => manager.getCachedClass(scope, fqn))
  }

  @SafeFieldForPreview
  override val alreadyConvertedPrefixes: Set[String] = Set("java.")

  override def getText: String = ScalaBundle.message("convert.scala.to.java.collection.hint")

  override def getFamilyName: String = ConvertScalaToJavaCollectionIntention.getFamilyName
}

object ConvertScalaToJavaCollectionIntention {
  def getFamilyName: String = ScalaBundle.message("convert.scala.to.java.collection.name")
}
