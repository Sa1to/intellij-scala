package org.jetbrains.plugins.scala.lang.completion

import com.intellij.codeInsight.completion._
import com.intellij.util.ProcessingContext
import com.intellij.codeInsight.lookup.LookupElementBuilder
import filters.definitions.{DefTypeFilter, ValueDefinitionFilter, DefinitionsFilter}
import filters.expression._
import filters.modifiers.{CaseFilter, ImplicitFilter, ModifiersFilter}
import com.intellij.psi.filters.position.{LeftNeighbour, FilterPattern}
import com.intellij.psi.filters.{TextFilter, AndFilter, NotFilter, ElementFilter}
import com.intellij.patterns.PlatformPatterns
import filters.other._
import filters.toplevel.{TemplateFilter, TraitFilter, ImportFilter, PackageFilter}
import handlers.ScalaKeywordInsertHandler
import lookups.LookupElementManager

/**
 * User: Alexander Podkhalyuzin
 * Date: 17.09.2009
 */

class ScalaKeywordCompletionContributor extends CompletionContributor {
  private def registerStandardCompletion(filter: ElementFilter, keywords: String*) {
    extend(CompletionType.BASIC, PlatformPatterns.psiElement.
            and(new FilterPattern(new AndFilter(new NotFilter(new LeftNeighbour(new TextFilter("."))), filter))),
      new CompletionProvider[CompletionParameters] {
        def addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
          for (keyword <- keywords) {
            result.addElement(LookupElementManager.getKeywrodLookupElement(keyword, parameters.getPosition))
          }
        }
      })
  }

  private def registerTypeAfterDotCompletion(filter: ElementFilter, keywords: String*) {
    extend(CompletionType.BASIC, PlatformPatterns.psiElement.
            and(new FilterPattern(new AndFilter(new LeftNeighbour(new TextFilter(".")), filter))),
      new CompletionProvider[CompletionParameters] {
        def addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
          for (keyword <- keywords) {
            result.addElement(LookupElementManager.getKeywrodLookupElement(keyword, parameters.getPosition))
          }
        }
      })
  }

  registerStandardCompletion(new PackageFilter, "package")
  registerStandardCompletion(new ExpressionFilter, "true", "false", "null", "new", "super", "this")
  registerStandardCompletion(new ModifiersFilter, "private", "protected", "override",
    "abstract", "final", "sealed", "implicit", "lazy")
  registerStandardCompletion(new ImplicitFilter, "implicit")
  registerStandardCompletion(new CaseFilter, "case")
  registerStandardCompletion(new ImportFilter, "import")
  registerStandardCompletion(new TemplateFilter, "class", "object")
  registerStandardCompletion(new TraitFilter, "trait")
  registerStandardCompletion(new DefinitionsFilter, "val", "var")
  registerStandardCompletion(new ValueDefinitionFilter, "val")
  registerStandardCompletion(new StatementFilter, "for", "while", "do", "try", "return", "throw", "if")
  registerStandardCompletion(new WhileFilter, "while")
  registerStandardCompletion(new CatchFilter, "catch")
  registerStandardCompletion(new FinallyFilter, "finally")
  registerStandardCompletion(new ElseFilter, "else")
  registerStandardCompletion(new ExtendsFilter, "extends")
  registerStandardCompletion(new YieldFilter, "yield")
  registerStandardCompletion(new WithFilter, "with")
  registerStandardCompletion(new DefTypeFilter, "def", "type")
  registerStandardCompletion(new ForSomeFilter, "forSome")
  registerStandardCompletion(new MatchFilter, "match")
  registerTypeAfterDotCompletion(new TypeFilter, "type")
  registerStandardCompletion(new IfFilter, "if")
}