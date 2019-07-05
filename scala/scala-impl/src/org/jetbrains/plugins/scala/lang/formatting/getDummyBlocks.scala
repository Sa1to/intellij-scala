package org.jetbrains.plugins.scala
package lang
package formatting

import java.util

import com.intellij.formatting._
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.psi._
import com.intellij.psi.codeStyle.{CodeStyleSettings, CommonCodeStyleSettings}
import com.intellij.psi.tree._
import com.intellij.psi.util.PsiTreeUtil
import org.apache.commons.lang3.StringUtils
import org.jetbrains.plugins.scala.extensions.{PsiElementExt, _}
import org.jetbrains.plugins.scala.lang.formatting.ScalaWrapManager._
import org.jetbrains.plugins.scala.lang.formatting.getDummyBlocks._
import org.jetbrains.plugins.scala.lang.formatting.processors._
import org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes._
import org.jetbrains.plugins.scala.lang.parser.util.ParserUtils
import org.jetbrains.plugins.scala.lang.parser.{ScCodeBlockElementType, ScalaElementType}
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns._
import org.jetbrains.plugins.scala.lang.psi.api.base.types._
import org.jetbrains.plugins.scala.lang.psi.api.base.{ScInterpolatedStringLiteral, ScLiteral}
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.expr.xml._
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params._
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates._
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.{ScModifierListOwner, ScPackaging}
import org.jetbrains.plugins.scala.lang.scaladoc.lexer.ScalaDocTokenType
import org.jetbrains.plugins.scala.lang.scaladoc.parser.ScalaDocElementTypes
import org.jetbrains.plugins.scala.lang.scaladoc.psi.api.{ScDocComment, ScDocTag}
import org.jetbrains.plugins.scala.project.UserDataHolderExt
import org.jetbrains.plugins.scala.util.MultilineStringUtil
import org.jetbrains.plugins.scala.util.MultilineStringUtil.MultilineQuotes

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

// TODO: rename it to some Builder/Producer/etc...
object getDummyBlocks {
  private type InterpolatedPointer = SmartPsiElementPointer[ScInterpolatedStringLiteral]
  private val alignmentsMapKey: Key[mutable.Map[InterpolatedPointer, (Alignment, Alignment)]] = Key.create("alingnments.map")
  private val fieldGroupAlignmentKey: Key[Alignment] = Key.create("field.group.alignment.key")
  private val multiLevelAlignmentKey: Key[mutable.Map[IElementType, List[ElementPointerAlignmentStrategy]]] = Key.create("multilevel.alignment")

  private val InfixElementsTokenSet = TokenSet.create(
    ScalaElementType.INFIX_EXPR,
    ScalaElementType.INFIX_PATTERN,
    ScalaElementType.INFIX_TYPE
  )

  private val FieldGroupSubBlocksTokenSet = TokenSet.orSet(
    TokenSet.create(tCOLON, tASSIGN),
    VAL_VAR_TOKEN_SET
  )

  private val FunctionTypeTokenSet = TokenSet.create(
    tFUNTYPE,
    tFUNTYPE_ASCII
  )

  def apply(block: ScalaBlock): getDummyBlocks = new getDummyBlocks(block)

  // keeps info for interpolated multiline string literal:
  // (opening quotes alignment, margin char alignment)
  private def alignmentsMap(project: Project): mutable.Map[InterpolatedPointer, (Alignment, Alignment)] = {
    project.getOrUpdateUserData(alignmentsMapKey, mutable.Map[InterpolatedPointer, (Alignment, Alignment)]())
  }

  private def cachedAlignment(literal: ScInterpolatedStringLiteral): Option[(Alignment, Alignment)] = {
    alignmentsMap(literal.getProject).collectFirst {
      case (pointer, alignment) if pointer.getElement == literal => alignment
    }
  }

  private def multiLevelAlignmentMap(project: Project): mutable.Map[IElementType, List[ElementPointerAlignmentStrategy]] = {
    project.getOrUpdateUserData(multiLevelAlignmentKey, mutable.Map[IElementType, List[ElementPointerAlignmentStrategy]]())
  }

  private def isCorrectBlock(node: ASTNode): Boolean = {
    StringUtils.isNotBlank(node.getChars)
  }

  private class StringLineScalaBlock(myTextRange: TextRange, mainNode: ASTNode, myParentBlock: ScalaBlock,
                                     myAlignment: Alignment, myIndent: Indent, myWrap: Wrap, mySettings: CodeStyleSettings)
    extends ScalaBlock(myParentBlock, mainNode, null, myAlignment, myIndent, myWrap, mySettings) {

    override def getTextRange: TextRange = myTextRange
    override def isLeaf = true
    override def isLeaf(node: ASTNode): Boolean = true
    override def getChildAttributes(newChildIndex: Int): ChildAttributes = new ChildAttributes(Indent.getNoneIndent, null)
    override def getSpacing(child1: Block, child2: Block): Spacing = Spacing.getReadOnlySpacing
    override def getSubBlocks: util.List[Block] = {
      if (subBlocks == null) {
        subBlocks = new util.ArrayList[Block]()
      }
      subBlocks
    }
  }
}

//noinspection RedundantDefaultArgument
class getDummyBlocks(private val block: ScalaBlock) {
  private val settings: CodeStyleSettings = block.settings
  private val commonSettings: CommonCodeStyleSettings = settings.getCommonSettings(ScalaLanguage.INSTANCE)
  private implicit val scalaSettings: ScalaCodeStyleSettings = settings.getCustomSettings(classOf[ScalaCodeStyleSettings])

  // shortcuts to simplify long conditions that operate with settings
  @inline private def cs = commonSettings
  @inline private def ss = scalaSettings

  // TODO: there are quite many unnecessary array allocations and copies, consider passing
  //  mutable buffer/list to submethods, and measure the performance!
  def apply(firstNode: ASTNode, lastNode: ASTNode): util.ArrayList[Block] = {
    if (lastNode != null) {
      applyInner(firstNode, lastNode)
    } else {
      applyInner(firstNode)
    }
  }

  private def applyInner(node: ASTNode): util.ArrayList[Block] = {
    val children = node.getChildren(null)
    val subBlocks = new util.ArrayList[Block]

    node.getPsi match {
      case _: ScValue | _: ScVariable if cs.ALIGN_GROUP_FIELD_DECLARATIONS =>
        subBlocks.addAll(getFieldGroupSubBlocks(node))
        return subBlocks
      case _: ScCaseClause if ss.ALIGN_IN_COLUMNS_CASE_BRANCH =>
        subBlocks.addAll(getCaseClauseGroupSubBlocks(node))
        return subBlocks
      case _: ScIf =>
        val alignment = if (ss.ALIGN_IF_ELSE) Alignment.createAlignment
        else null
        subBlocks.addAll(getIfSubBlocks(node, alignment))
        return subBlocks
      case _: ScInfixExpr | _: ScInfixPattern | _: ScInfixTypeElement =>
        subBlocks.addAll(getInfixBlocks(node))
        return subBlocks
      case extendsBlock: ScExtendsBlock =>
        subBlocks.addAll(getExtendsSubBlocks(node, extendsBlock))
        return subBlocks
      case _: ScFor =>
        subBlocks.addAll(getForSubBlocks(node, children))
        return subBlocks
      case _: ScReferenceExpression | _: ScThisReference | _: ScSuperReference =>
        subBlocks.addAll(getMethodCallOrRefExprSubBlocks(node))
        return subBlocks
      case _: ScMethodCall =>
        subBlocks.addAll(getMethodCallOrRefExprSubBlocks(node))
        return subBlocks
      case _: ScLiteral if node.getFirstChildNode != null &&
        node.getFirstChildNode.getElementType == tMULTILINE_STRING && ss.supportMultilineString =>
        subBlocks.addAll(getMultilineStringBlocks(node))
        return subBlocks
      case pack: ScPackaging if pack.isExplicit =>
        val correctChildren = children.filter(isCorrectBlock)
        val (beforeOpenBrace, afterOpenBrace) = correctChildren.span(_.getElementType != tLBRACE)
        val hasValidTail = afterOpenBrace.nonEmpty && afterOpenBrace.head.getElementType == tLBRACE &&
          afterOpenBrace.last.getElementType == tRBRACE
        for (child <- if (hasValidTail) beforeOpenBrace else correctChildren) {
          subBlocks.add(subBlock(child))
        }
        if (hasValidTail) {
          subBlocks.add(subBlock(afterOpenBrace.head, afterOpenBrace.last))
        }
        return subBlocks
      case _: ScDocComment =>
        var scalaDocPrevChildTag: Option[String] = None
        var contextAlignment: Alignment = Alignment.createAlignment(true)
        val alignment = createAlignment(node)
        for (child <- children if isCorrectBlock(child)) {
          val context = (child.getElementType match {
            case ScalaDocElementTypes.DOC_TAG =>
              val currentTag = Option(child.getFirstChildNode).filter(_.getElementType == ScalaDocTokenType.DOC_TAG_NAME).map(_.getText)
              if (scalaDocPrevChildTag.isEmpty || scalaDocPrevChildTag != currentTag) {
                contextAlignment = Alignment.createAlignment(true)
              }
              scalaDocPrevChildTag = currentTag
              Some(contextAlignment)
            case _ => None
          }).map(a => new SubBlocksContext(alignment = Some(a)))
          subBlocks.add(subBlock(child, null, alignment, context = context))
        }
        return subBlocks
      case _ if node.getElementType == ScalaDocElementTypes.DOC_TAG =>
        val docTag = node.getPsi.asInstanceOf[ScDocTag]

        @tailrec
        def getNonWsSiblings(firstNode: ASTNode, acc: List[ASTNode] = List()): List[ASTNode] =
          if (firstNode == null) {
            acc.reverse
          } else if (ScalaDocNewlinedPreFormatProcessor.isWhiteSpace(firstNode)) {
            getNonWsSiblings(firstNode.getTreeNext, acc)
          } else {
            getNonWsSiblings(firstNode.getTreeNext, firstNode :: acc)
          }

        val childBlocks = getNonWsSiblings(docTag.getFirstChild.getNode)
        //TODO whitespace between tag name and tag parameter (like in @param x) has type "DOC_COMMENT_DATA"
        //while it should be DOC_WHITESPACE
        childBlocks match {
          case tagName :: space :: tagParameter :: tail
            if Option(docTag.getValueElement).map(_.getNode).contains(tagParameter) =>

            subBlocks.add(subBlock(tagName))
            subBlocks.add(subBlock(space))
            subBlocks.add(subBlock(tagParameter, tail.lastOption.orNull))
          case tagName :: tail
            if Option(docTag.getNameElement).map(_.getNode).contains(tagName) =>

            subBlocks.add(subBlock(tagName))
            if (tail.nonEmpty) {
              val (leadingAsterisks, other) = tail.span(_.getElementType == ScalaDocTokenType.DOC_COMMENT_LEADING_ASTERISKS)
              leadingAsterisks.foreach { a =>
                subBlocks.add(subBlock(a))
              }
              if (other.nonEmpty) {
                subBlocks.add(subBlock(other.head, other.last))
              }
            }
          case _ =>
        }
        return subBlocks
      case interpolated: ScInterpolatedStringLiteral =>
        //create and store alignment; required for support of multi-line interpolated strings (SCL-8665)
        alignmentsMap(interpolated.getProject).put(interpolated.createSmartPointer, buildQuotesAndMarginAlignments)
      case psi@(_: ScValueOrVariable | _: ScFunction) if node.getFirstChildNode.getPsi.isInstanceOf[PsiComment] =>
        val childrenFiltered: Array[ASTNode] = children.filter(isCorrectBlock)
        val childHead :: childTail = childrenFiltered.toList
        subBlocks.add(subBlock(childHead))
        val indent: Indent = {
          val prevNonWsNode: Option[PsiElement] = psi.prevSibling match {
            case Some(prev@Whitespace(s)) =>
              if (s.contains("\n")) None
              else prev.prevSibling
            case prev =>
              prev
          }
          prevNonWsNode.map(_.elementType) match {
            case Some(`tLBRACE` | `tLPARENTHESIS`) if scalaSettings.KEEP_COMMENTS_ON_SAME_LINE =>
              Indent.getNormalIndent
            case _ =>
              Indent.getNoneIndent
          }
        }
        subBlocks.add(subBlock(childTail.head, childTail.last, null, Some(indent)))
        return subBlocks
      case _ =>
    }

    val alignment: Alignment = createAlignment(node)
    for (child <- children if isCorrectBlock(child)) {
      val childAlignment: Alignment = {
        node.getPsi match {
          case params: ScParameters =>
            val firstParameterStartsFromNewLine =
              params.clauses.headOption.flatMap(_.parameters.headOption).forall(_.startsFromNewLine()) ||
                commonSettings.METHOD_PARAMETERS_LPAREN_ON_NEXT_LINE
            if (firstParameterStartsFromNewLine && !scalaSettings.INDENT_FIRST_PARAMETER) null
            else alignment
          case _: ScParameterClause =>
            child.getElementType match {
              case `tRPARENTHESIS` | `tLPARENTHESIS` => null
              case _ => alignment
            }
          case _: ScArgumentExprList =>
            child.getElementType match {
              case `tRPARENTHESIS` if cs.ALIGN_MULTILINE_PARAMETERS_IN_CALLS => alignment
              case `tRPARENTHESIS` | `tLPARENTHESIS` => null
              case ScCodeBlockElementType.BlockExpression if ss.DO_NOT_ALIGN_BLOCK_EXPR_PARAMS => null
              case _ if cs.ALIGN_MULTILINE_PARAMETERS_IN_CALLS => alignment
              case _ => null
            }
          case patt: ScPatternArgumentList =>
            child.getElementType match {
              case `tRPARENTHESIS` if cs.ALIGN_MULTILINE_PARAMETERS_IN_CALLS && patt.missedLastExpr => alignment
              case `tRPARENTHESIS` | `tLPARENTHESIS` => null
              case ScCodeBlockElementType.BlockExpression if ss.DO_NOT_ALIGN_BLOCK_EXPR_PARAMS => null
              case _ if cs.ALIGN_MULTILINE_PARAMETERS_IN_CALLS => alignment
              case _ => null
            }
          case _: ScMethodCall | _: ScReferenceExpression =>
            if (child.getElementType == tIDENTIFIER &&
              child.getPsi.getParent.isInstanceOf[ScReferenceExpression] &&
              child.getPsi.getParent.asInstanceOf[ScReferenceExpression].qualifier.isEmpty) null
            else if (child.getPsi.isInstanceOf[ScExpression]) null
            else alignment
          case _: ScXmlStartTag | _: ScXmlEmptyTag =>
            child.getElementType match {
              case ScalaElementType.XML_ATTRIBUTE => alignment
              case _ => null
            }
          case _: ScXmlElement =>
            child.getElementType match {
              case ScalaElementType.XML_START_TAG | ScalaElementType.XML_END_TAG => alignment
              case _ => null
            }
          case _: ScParameter =>
            child.getElementType match {
              case `tCOLON` if ss.ALIGN_TYPES_IN_MULTILINE_DECLARATIONS =>
                child.getPsi.nullSafe.map(_.getParent).map(_.getParent).map { rootPsi =>
                  val map = multiLevelAlignmentMap(rootPsi.getProject)
                  map.get(tCOLON).flatMap(_.find(_.shouldAlign(child))) match {
                    case Some(multiAlignment) => multiAlignment.getAlignment
                    case None =>
                      val multiAlignment = ElementPointerAlignmentStrategy.typeMultiLevelAlignment(rootPsi)
                      assert(multiAlignment.shouldAlign(child))
                      map.update(tCOLON, multiAlignment :: map.getOrElse(tCOLON, List()))
                      multiAlignment.getAlignment
                  }
                }.getOrElse(alignment)
              case _ => alignment
            }
          case literal: ScInterpolatedStringLiteral if child.getElementType == tINTERPOLATED_STRING_END =>
            cachedAlignment(literal).map(_._1).orNull
          case _ =>
            alignment
        }
      }

      val needFlattenInterpolatedStrings = child.getFirstChildNode == null &&
        child.getElementType == tINTERPOLATED_MULTILINE_STRING &&
        ss.supportMultilineString
      if (needFlattenInterpolatedStrings) {
        subBlocks.addAll(getMultilineStringBlocks(child))
      } else {
        subBlocks.add(subBlock(child, null, childAlignment))
      }
    }

    subBlocks
  }

  private def getCaseClauseGroupSubBlocks(node: ASTNode): util.ArrayList[Block] = {
    val children = node.getChildren(null).filter(isCorrectBlock)
    val subBlocks = new util.ArrayList[Block]

    def getPrevGroupNode(nodePsi: PsiElement) = {
      var prev = nodePsi.getPrevSibling
      var breaks = 0

      def isOk(psi: PsiElement): Boolean = psi match {
        case _: ScCaseClause => true
        case _: PsiComment => false
        case _: PsiWhiteSpace =>
          breaks += psi.getText.count(_ == '\n')
          false
        case _ =>
          breaks += 2
          false
      }

      while (prev != null && breaks <= 1 && !isOk(prev)) {
        prev = prev.getPrevSibling
      }
      if (breaks == 1 && prev != null) prev.getNode
      else null
    }

    var prevChild: ASTNode = null
    for (child <- children) {
      val childAlignment = getChildAlignment(node, child)(getPrevGroupNode)(FunctionTypeTokenSet)
      subBlocks.add(subBlock(child, null, childAlignment))
      prevChild = child
    }

    subBlocks
  }

  private def getFieldGroupSubBlocks(node: ASTNode): util.ArrayList[Block] = {
    val children = node.getChildren(null).filter(isCorrectBlock)
    val subBlocks = new util.ArrayList[Block]

    def getPrevGroupNode(nodePsi: PsiElement) = {
      var prev = nodePsi.getPrevSibling
      var breaks = 0

      def isOk(psi: PsiElement): Boolean = psi match {
        case ElementType(t) if t == tSEMICOLON =>
          false
        case _: ScVariableDeclaration | _: ScValueDeclaration if nodePsi.is[ScPatternDefinition, ScVariableDefinition] =>
          breaks += 2
          false
        case _: ScVariableDefinition | _: ScPatternDefinition if nodePsi.is[ScValueDeclaration, ScValueDeclaration] =>
          breaks += 2
          false
        case _: ScVariable | _: ScValue =>
          def hasEmptyModifierList(psi: PsiElement): Boolean = psi match {
            case mod: ScModifierListOwner if mod.getModifierList.getTextLength == 0 => true
            case _ => false
          }

          if (hasEmptyModifierList(psi) != hasEmptyModifierList(nodePsi)) {
            breaks += 2
            false
          } else {
            true
          }
        case _: PsiComment =>
          false
        case _: PsiWhiteSpace =>
          breaks += psi.getText.count(_ == '\n')
          false
        case _ =>
          breaks += 2
          false
      }

      while (prev != null && breaks <= 1 && !isOk(prev)) {
        prev = prev.getPrevSibling
      }
      if (breaks == 1 && prev != null) prev.getNode
      else null
    }

    var prevChild: ASTNode = null
    for (child <- children) {
      //TODO process rare case of first-line comment before one of the fields  for SCL-10000 here
      val childAlignment = getChildAlignment(node, child)(getPrevGroupNode)(FieldGroupSubBlocksTokenSet)
      subBlocks.add(subBlock(child, null, childAlignment))
      prevChild = child
    }
    subBlocks
  }

  @tailrec
  private def getChildAlignment(node: ASTNode, child: ASTNode)
                               (getPrevGroupNode: PsiElement => ASTNode)
                               (implicit tokenSet: TokenSet): Alignment = {
    def createNewAlignment: Alignment = {
      val alignment = Alignment.createAlignment(true)
      child.getPsi.putUserData(fieldGroupAlignmentKey, alignment)
      alignment
    }

    val prev = getPrevGroupNode(node.getPsi)
    child.getElementType match {
      case elementType if tokenSet.contains(elementType) =>
        prev match {
          case null => createNewAlignment
          case _ =>
            prev.findChildByType(elementType) match {
              case null => getChildAlignment(prev, child)(getPrevGroupNode)
              case prevChild =>
                val newAlignment = prevChild.getPsi.getUserData(fieldGroupAlignmentKey) match {
                  case null => createNewAlignment
                  case alignment => alignment
                }
                child.getPsi.putUserData(fieldGroupAlignmentKey, newAlignment)
                newAlignment
            }
        }
      case _ => null
    }
  }

  private def getExtendsSubBlocks(node: ASTNode, extBlock: ScExtendsBlock): util.ArrayList[Block] = {
    val subBlocks = new util.ArrayList[Block]

    val firstChild = extBlock.getFirstChild
    if (firstChild == null) return subBlocks
    val tempBody = extBlock.templateBody

    val lastChild = tempBody.map(_.getPrevSiblingNotWhitespace).getOrElse(extBlock.getLastChild)
    if (lastChild != null) {
      val alignment =
        if (ss.ALIGN_EXTENDS_WITH == ScalaCodeStyleSettings.ALIGN_TO_EXTENDS) Alignment.createAlignment(false)
        else null
      subBlocks.add(subBlock(firstChild.getNode, lastChild.getNode, alignment))
    }

    tempBody match {
      case Some(x) =>
        subBlocks.add(subBlock(x.getNode))
      case _ =>
    }

    subBlocks
  }

  private def getForSubBlocks(node: ASTNode, children: Array[ASTNode]): util.ArrayList[Block] = {
    val subBlocks = new util.ArrayList[Block]()

    var prevChild: ASTNode = null
    def addTail(tail: List[ASTNode]): Unit = {
      for (child <- tail) {
        if (child.getElementType != kYIELD) {
          if (prevChild != null && prevChild.getElementType == kYIELD) {
            subBlocks.add(subBlock(prevChild, child))
          } else {
            subBlocks.add(subBlock(child, null))
          }
        }
        prevChild = child
      }
      if (prevChild != null && prevChild.getElementType == kYIELD) {
        //add a block for 'yield' in case of incomplete for statement (expression after yield is missing)
        subBlocks.add(subBlock(prevChild, null))
      }
    }

    @tailrec
    def addFor(children: List[ASTNode]): Unit = children match {
      case forWord :: tail if forWord.getElementType == kFOR =>
        subBlocks.add(subBlock(forWord, null))
        addFor(tail)
      case lParen :: tail if LBRACE_LPARENT_TOKEN_SET.contains(lParen.getElementType) =>
        val closingType =
          if (lParen.getElementType == tLPARENTHESIS) tRPARENTHESIS
          else tRBRACE
        val afterСlosingParent = tail.dropWhile(_.getElementType != closingType)
        afterСlosingParent match {
          case Nil =>
            addTail(children)
          case rParent :: yieldNodes =>
            val enumerators = tail.head
            val context = if (commonSettings.ALIGN_MULTILINE_FOR && !enumerators.getPsi.startsFromNewLine()) {
              val alignment = Alignment.createAlignment()
              Some(SubBlocksContext(Map(rParent -> alignment, enumerators -> alignment)))
            } else {
              None
            }
            subBlocks.add(subBlock(lParen, rParent, context = context))
            addTail(yieldNodes)
        }
      case _ =>
        addTail(children)
    }
    addFor(children.filter(isCorrectBlock).toList)

    subBlocks
  }

  private def getIfSubBlocks(node: ASTNode, alignment: Alignment): util.ArrayList[Block] = {
    val subBlocks = new util.ArrayList[Block]

    val firstChildNode = node.getFirstChildNode
    var child = firstChildNode
    while (child.getTreeNext != null && child.getTreeNext.getElementType != kELSE) {
      child = child.getTreeNext
    }

    val firstBlock = subBlock(firstChildNode, child, alignment)
    subBlocks.add(firstBlock)

    if (child.getTreeNext != null) {
      val firstChild = child.getTreeNext
      child = firstChild
      while (child.getTreeNext != null) {
        child.getTreeNext.getPsi match {
          case _: ScIf if cs.SPECIAL_ELSE_IF_TREATMENT =>
            subBlocks.add(subBlock(firstChild, child, alignment, Some(firstBlock.indent)))
            subBlocks.addAll(getIfSubBlocks(child.getTreeNext, alignment))
          case _ =>
        }
        child = child.getTreeNext
      }
      if (subBlocks.size == 1) {
        subBlocks.add(subBlock(firstChild, child, alignment, Some(firstBlock.indent)))
      }
    }

    subBlocks
  }

  private def interpolatedRefLength(node: ASTNode): Int = {
    if (node.getElementType == tINTERPOLATED_MULTILINE_STRING) {
      node.getPsi.getParent match {
        case str: ScInterpolatedStringLiteral => str.referenceName.length
        case _ => 0
      }
    } else 0
  }

  private def buildQuotesAndMarginAlignments: (Alignment, Alignment) = {
    val quotesAlignment = if (scalaSettings.MULTILINE_STRING_ALIGN_DANGLING_CLOSING_QUOTES) Alignment.createAlignment() else null
    val marginAlignment = Alignment.createAlignment(true)
    (quotesAlignment, marginAlignment)
  }

  private def getMultilineStringBlocks(node: ASTNode): util.ArrayList[Block] = {
    val subBlocks = new util.ArrayList[Block]

    val interpolatedOpt = Option(PsiTreeUtil.getParentOfType(node.getPsi, classOf[ScInterpolatedStringLiteral]))
    val (quotesAlignment, marginAlignment) =
      interpolatedOpt
        .flatMap(cachedAlignment)
        .getOrElse(buildQuotesAndMarginAlignments)

    val wrap = Wrap.createWrap(WrapType.NONE, true)
    val marginChar = MultilineStringUtil.getMarginChar(node.getPsi)
    val marginIndent = Indent.getSpaceIndent(ss.MULTILINE_STRING_MARGIN_INDENT + interpolatedRefLength(node), true)

    def relativeRange(start: Int, end: Int, shift: Int = 0): TextRange =
      TextRange.from(node.getStartOffset + shift + start, end - start)

    val lines = node.getText.split("\n")
    var acc = 0
    lines.foreach { line =>
      val trimmedLine = line.trim()
      val lineLength = line.length
      val linePrefixLength = if (settings.useTabCharacter(ScalaFileType.INSTANCE)) {
        val tabsCount = line.prefixLength(_ == '\t')
        tabsCount + line.substring(tabsCount).prefixLength(_ == ' ')
      } else {
        line.prefixLength(_ == ' ')
      }

      if (trimmedLine.startsWith(marginChar)) {
        val marginRange = relativeRange(linePrefixLength, linePrefixLength + 1, acc)
        subBlocks.add(new StringLineScalaBlock(marginRange, node, block, marginAlignment, marginIndent, null, settings))
        val contentRange = relativeRange(linePrefixLength + 1, lineLength, acc)
        subBlocks.add(new StringLineScalaBlock(contentRange, node, block, null, Indent.getNoneIndent, wrap, settings))
      } else if (trimmedLine.length > 0) {
        val (range, myIndent, myAlignment) =
          if (trimmedLine.startsWith(MultilineQuotes)) {
            if (acc == 0) {
              val hasMarginOnFirstLine = trimmedLine.charAt(MultilineQuotes.length.min(trimmedLine.length - 1)) == '|'
              if (hasMarginOnFirstLine && lineLength > 3) {
                val range = relativeRange(0, 3)
                val marginBlock = new StringLineScalaBlock(range, node, block, quotesAlignment, Indent.getNoneIndent, null, settings)
                subBlocks.add(marginBlock)
                //now, return block parameters for text after the opening quotes
                (relativeRange(3, lineLength), Indent.getNoneIndent, marginAlignment)
              } else {
                (relativeRange(linePrefixLength, lineLength), Indent.getNoneIndent, quotesAlignment)
              }
            } else {
              (relativeRange(linePrefixLength, lineLength, acc), Indent.getNoneIndent, quotesAlignment)
            }
          } else {
            (relativeRange(0, lineLength, acc), Indent.getAbsoluteNoneIndent, null)
          }
        subBlocks.add(new StringLineScalaBlock(range, node, block, myAlignment, myIndent, null, settings))
      }

      acc += lineLength + 1
    }

    subBlocks
  }

  private def getInfixBlocks(node: ASTNode, parentAlignment: Alignment = null): util.ArrayList[Block] = {
    val subBlocks = new util.ArrayList[Block]
    val children = node.getChildren(null)
    val alignment =
      if (parentAlignment != null) parentAlignment
      else createAlignment(node)
    for (child <- children) {
      if (InfixElementsTokenSet.contains(child.getElementType) && infixPriority(node) == infixPriority(child)) {
        subBlocks.addAll(getInfixBlocks(child, alignment))
      } else if (isCorrectBlock(child)) {
        subBlocks.add(subBlock(child, null, alignment))
      }
    }
    subBlocks
  }

  private def infixPriority(node: ASTNode): Int = node.getPsi match {
    case inf: ScInfixExpr => ParserUtils.priority(inf.operation.getText, assignments = true)
    case inf: ScInfixPattern => ParserUtils.priority(inf.operation.getText, assignments = false)
    case inf: ScInfixTypeElement => ParserUtils.priority(inf.operation.getText, assignments = false)
    case _ => 0
  }

  private def getMethodCallOrRefExprSubBlocks(node: ASTNode): util.ArrayList[Block] = {
    val dotAlignment = if (cs.ALIGN_MULTILINE_CHAINED_METHODS) Alignment.createAlignment() else null
    val dotWrap = block.suggestedWrap

    val result = new util.ArrayList[Block]

    @scala.annotation.tailrec
    def collectChainedMethodCalls(
      node: ASTNode,
      dotFollowedByNewLine: Boolean = false,
      delegatedChildren: List[ASTNode] = List(),
      delegatedContext: Map[ASTNode, SubBlocksContext] = Map(),
    ): Unit = {
      if (node.getPsi.isInstanceOf[ScLiteral]) {
        result.add(subBlock(node, null))
        for (child <- delegatedChildren.filter(isCorrectBlock)) {
          result.add(subBlock(child, null))
        }
        return
      }

      val alignment = createAlignment(node)
      val childrenAll = node.getChildren(null).filter(isCorrectBlock).toList
      val (comments, children) = childrenAll.partition(isComment)

      //don't check for element types other then absolutely required - they do not matter
      children match {
        case caller :: args :: Nil if args.getPsi.isInstanceOf[ScArgumentExprList] =>
          collectChainedMethodCalls(caller, dotFollowedByNewLine, args :: delegatedChildren ++ comments)

        case expr :: dot :: id :: Nil if dot.getElementType == tDOT =>
          // delegatedChildren can be args or typeArgs
          val idAdditionalNodes = {
            // using Set we imply that ASTNode equals and hashCode methods are lightweight (default implementation)
            val filterOutNodes = delegatedContext.values.flatMap(_.additionalNodes).toSet
            sorted(delegatedChildren.filterNot(filterOutNodes.contains))
          }
          val context = SubBlocksContext(id, idAdditionalNodes, Some(dotAlignment), delegatedContext)
          result.add(subBlock(dot, lastNode(id :: delegatedChildren), dotAlignment, wrap = Some(dotWrap), context = Some(context)))

          assert(childrenAll.head.eq(expr), "assuming that first child is expr and comments can't go before it")
          val commentsBeforeDot = childrenAll.tail.takeWhile(isComment)
          commentsBeforeDot.foreach { comment =>
            val commentAlign = if (comment.getPsi.startsFromNewLine()) dotAlignment else null
            result.add(subBlock(comment, comment, commentAlign, wrap = Some(dotWrap)))
          }

          val dotFollowedByNewLine = dot.getPsi.followedByNewLine()
          collectChainedMethodCalls(expr, dotFollowedByNewLine)

        case expr :: typeArgs :: Nil if typeArgs.getPsi.isInstanceOf[ScTypeArgs] =>
          if (expr.getChildren(null).length == 1) {
            val actualAlignment = if (dotFollowedByNewLine) dotAlignment else alignment
            val context = SubBlocksContext(typeArgs, sorted(delegatedChildren))
            result.add(subBlock(expr, lastNode(typeArgs :: delegatedChildren), actualAlignment, context = Some(context)))
          } else {
            collectChainedMethodCalls(
              expr, dotFollowedByNewLine,
              typeArgs :: delegatedChildren ++ comments,
              Map(typeArgs -> new SubBlocksContext(sorted(delegatedChildren)))
            )
          }

        case expr :: Nil =>
          val actualAlignment = if (dotFollowedByNewLine) dotAlignment else alignment
          val context = SubBlocksContext(expr, delegatedChildren)
          result.add(subBlock(expr, lastNode(delegatedChildren), actualAlignment, context = Some(context)))

        case _ =>
          val childrenWithDelegated = children ++ delegatedChildren
          for (child <- childrenWithDelegated.filter(isCorrectBlock)) {
            result.add(subBlock(child, null))
          }
      }
    }

    collectChainedMethodCalls(node)

    // we need to sort blocks because we add them in wrong order to make inner method tail recursive
    util.Collections.sort(result, util.Comparator.comparingInt[Block](_.getTextRange.getStartOffset))

    result
  }

  @inline
  private def lastNode(nodes: Seq[ASTNode]): ASTNode = sorted(nodes).lastOption.orNull

  @inline
  private def sorted(nodes: Seq[ASTNode]): Seq[ASTNode] = nodes.sortBy(_.getTextRange.getStartOffset)

  @inline
  private def isComment(node: ASTNode) = COMMENTS_TOKEN_SET.contains(node.getElementType)

  private def createAlignment(node: ASTNode): Alignment = {
    if (mustAlignment(node)) Alignment.createAlignment
    else null
  }

  private def mustAlignment(node: ASTNode): Boolean = {
    import commonSettings._
    node.getPsi match {
      case _: ScXmlStartTag => true //todo:
      case _: ScXmlEmptyTag => true //todo:
      case _: ScParameters if ALIGN_MULTILINE_PARAMETERS => true
      case _: ScParameterClause if ALIGN_MULTILINE_PARAMETERS => true
      case _: ScArgumentExprList if ALIGN_MULTILINE_PARAMETERS_IN_CALLS => true
      case _: ScPatternArgumentList if ALIGN_MULTILINE_PARAMETERS_IN_CALLS => true
      case _: ScEnumerators if ALIGN_MULTILINE_FOR => true
      case _: ScParenthesisedExpr if ALIGN_MULTILINE_PARENTHESIZED_EXPRESSION => true
      case _: ScParenthesisedTypeElement if ALIGN_MULTILINE_PARENTHESIZED_EXPRESSION => true
      case _: ScParenthesisedPattern if ALIGN_MULTILINE_PARENTHESIZED_EXPRESSION => true
      case _: ScInfixExpr if ALIGN_MULTILINE_BINARY_OPERATION => true
      case _: ScInfixPattern if ALIGN_MULTILINE_BINARY_OPERATION => true
      case _: ScInfixTypeElement if ALIGN_MULTILINE_BINARY_OPERATION => true
      case _: ScCompositePattern if ss.ALIGN_COMPOSITE_PATTERN => true
      case _: ScMethodCall | _: ScReferenceExpression | _: ScThisReference | _: ScSuperReference if ALIGN_MULTILINE_CHAINED_METHODS => true
      case _ => false
    }
  }

  private def applyInner(node: ASTNode, lastNode: ASTNode): util.ArrayList[Block] = {
    val subBlocks = new util.ArrayList[Block]

    def childBlock(child: ASTNode): ScalaBlock = {
      val lastNode = block.getChildBlockLastNode(child)
      val alignment = block.getCustomAlignment(child).orNull
      val context = block.subBlocksContext.flatMap(_.childrenAdditionalContexts.get(child))
      subBlock(child, lastNode, alignment, context = context)
    }

    if (insideScalaDocComment(node)) {
      applyInnerScaladoc(node, lastNode, subBlocks)
    } else {
      var child: ASTNode = node
      do {
        if (isCorrectBlock(child)) {
          if (child.getPsi.isInstanceOf[ScTemplateParents]) {
            subBlocks.addAll(getTemplateParentsBlocks(child))
          } else {
            subBlocks.add(childBlock(child))
          }
        }
      } while (child != lastNode && {
        child = child.getTreeNext
        child != null
      })
    }

    //it is not used right now, but could come in handy later
    for {
      context <- block.subBlocksContext
      additionalNode <- context.additionalNodes
    } {
      subBlocks.add(childBlock(additionalNode))
    }

    subBlocks
  }

  private def insideScalaDocComment(node: ASTNode): Boolean = {
    val insideIncompleteScalaDocTag =
      node.getTreeParent.nullSafe.exists(_.getElementType == ScalaDocElementTypes.DOC_TAG) &&
        node.getPsi.isInstanceOf[PsiErrorElement]
    ScalaDocTokenType.ALL_SCALADOC_TOKENS.contains(node.getElementType) || insideIncompleteScalaDocTag
  }

  private def applyInnerScaladoc(node: ASTNode, lastNode: ASTNode, subBlocks: util.List[Block]): Unit = {
    val children = ArrayBuffer[ASTNode]()
    var scaladocNode = node.getElementType match {
      case ScalaDocTokenType.DOC_TAG_VALUE_TOKEN =>
        subBlocks.add(subBlock(node, indent = Some(Indent.getNoneIndent)))
        node.getTreeNext
      case _ =>
        node
    }

    do {
      if (scaladocNode.getText.contains("\n")) {
        flattenChildren(scaladocNode, children)
      } else {
        children += scaladocNode
      }
    } while (scaladocNode != lastNode && (scaladocNode = scaladocNode.getTreeNext, true)._2)

    val normalAlignment =
      block.parentBlock.subBlocksContext.flatMap(_.alignment)
        .getOrElse(Alignment.createAlignment(true))

    children.view.filter(isCorrectBlock).foreach { child =>
      val firstSibling = node.getTreeParent.getFirstChildNode
      val childType = child.getElementType

      val isDataInsideDocTag =
        node.getTreeParent.getElementType == ScalaDocElementTypes.DOC_TAG &&
          childType != ScalaDocTokenType.DOC_WHITESPACE &&
          childType != ScalaDocTokenType.DOC_COMMENT_LEADING_ASTERISKS &&
          child != firstSibling &&
          firstSibling.getElementType == ScalaDocTokenType.DOC_TAG_NAME &&
          child.getText.trim.length > 0

      val (childAlignment, childWrap) =
        if (isDataInsideDocTag) {
          val docTagName = firstSibling.getText

          val alignment = docTagName match {
            case _ if childType == ScalaDocTokenType.DOC_INNER_CODE => null
            case _ if childType == ScalaDocTokenType.DOC_INNER_CLOSE_CODE_TAG => null
            case _ if childType == ScalaDocTokenType.DOC_INNER_CODE_TAG => null
            case "@param" | "@tparam" => if (ss.SD_ALIGN_PARAMETERS_COMMENTS) normalAlignment else null
            case "@return" => if (ss.SD_ALIGN_RETURN_COMMENTS) normalAlignment else null
            case "@throws" => if (ss.SD_ALIGN_EXCEPTION_COMMENTS) normalAlignment else null
            case _ => if (ss.SD_ALIGN_OTHER_TAGS_COMMENTS) normalAlignment else null
          }
          val noWrap = Wrap.createWrap(WrapType.NONE, false)
          (alignment, noWrap)
        } else {
          (null, arrangeSuggestedWrapForChild(block, child, block.suggestedWrap))
        }
      subBlocks.add(subBlock(child, null, childAlignment, wrap = Some(childWrap)))
    }
  }

  private def flattenChildren(multilineNode: ASTNode, buffer: ArrayBuffer[ASTNode]): Unit = {
    for (nodeChild <- multilineNode.getChildren(null)) {
      if (nodeChild.textContains('\n') && nodeChild.getFirstChildNode != null) {
        flattenChildren(nodeChild, buffer)
      } else {
        buffer += nodeChild
      }
    }
  }

  private def getTemplateParentsBlocks(node: ASTNode): util.ArrayList[Block] = {
    val subBlocks = new util.ArrayList[Block]

    import ScalaCodeStyleSettings._
    val alignSetting = ss.ALIGN_EXTENDS_WITH
    val alignment =
      if (alignSetting == ALIGN_TO_EXTENDS) block.getAlignment
      else Alignment.createAlignment(true)

    val children = node.getChildren(null)
    for (child <- children if isCorrectBlock(child)) {
      val actualAlignment = (child.getElementType, alignSetting) match {
        case (_, DO_NOT_ALIGN) => null
        case (`kWITH` | `kEXTENDS`, ON_FIRST_ANCESTOR) => null
        case _ => alignment
      }
      val lastNode = block.getChildBlockLastNode(child)
      val context = block.subBlocksContext.flatMap(_.childrenAdditionalContexts.get(child))
      subBlocks.add(subBlock(child, lastNode, actualAlignment, context = context))
    }
    subBlocks
  }

  private def subBlock(node: ASTNode,
                       lastNode: ASTNode = null,
                       alignment: Alignment = null,
                       indent: Option[Indent] = None,
                       wrap: Option[Wrap] = None,
                       context: Option[SubBlocksContext] = None): ScalaBlock = {
    val indentFinal = indent.getOrElse(ScalaIndentProcessor.getChildIndent(block, node))
    val wrapFinal = wrap.getOrElse(arrangeSuggestedWrapForChild(block, node, block.suggestedWrap))
    new ScalaBlock(block, node, lastNode, alignment, indentFinal, wrapFinal, settings, context)
  }
}