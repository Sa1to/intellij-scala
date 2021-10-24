package org.jetbrains.plugins.scala.lang.dfa.analysis.invocations.interprocedural

import com.intellij.codeInspection.dataFlow.interpreter.{DataFlowInterpreter, RunnerResult, StandardDataFlowInterpreter}
import com.intellij.codeInspection.dataFlow.jvm.JvmDfaMemoryStateImpl
import com.intellij.codeInspection.dataFlow.lang.ir.SimpleAssignmentInstruction
import com.intellij.codeInspection.dataFlow.memory.DfaMemoryState
import com.intellij.codeInspection.dataFlow.types.DfType
import com.intellij.codeInspection.dataFlow.value.{DfaValue, DfaValueFactory}
import com.intellij.psi.{PsiModifier, PsiModifierListOwner}
import org.jetbrains.plugins.scala.extensions.{ObjectExt, PsiModifierListOwnerExt}
import org.jetbrains.plugins.scala.lang.dfa.analysis.invocations.specialSupport.SpecialSupportUtils.{byNameParametersPresent, implicitParametersPresent}
import org.jetbrains.plugins.scala.lang.dfa.controlFlow.transformations.ScalaPsiElementTransformer
import org.jetbrains.plugins.scala.lang.dfa.controlFlow.{ScalaDfaControlFlowBuilder, ScalaDfaVariableDescriptor}
import org.jetbrains.plugins.scala.lang.dfa.invocationInfo.arguments.Argument
import org.jetbrains.plugins.scala.lang.dfa.invocationInfo.{InvocationInfo, InvokedElement}
import org.jetbrains.plugins.scala.lang.dfa.utils.ScalaDfaTypeUtils.unknownDfaValue
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScObject

object InterproceduralAnalysis {

  val InterproceduralAnalysisDepthLimit = 3

  def tryInterpretExternalMethod(invocationInfo: InvocationInfo, argumentValues: Map[Argument, DfaValue],
                                 currentAnalysedMethodInfo: AnalysedMethodInfo)
                                (implicit factory: DfaValueFactory): Option[DfType] = {
    invocationInfo.invokedElement match {
      case Some(InvokedElement(function: ScFunctionDefinition))
        if supportsInterproceduralAnalysis(function, invocationInfo, currentAnalysedMethodInfo) => function.body match {
        case Some(body) => val paramValues = mapArgumentValuesToParams(invocationInfo, function, argumentValues)
          analyseExternalMethodBody(function, body, paramValues, currentAnalysedMethodInfo)
        case _ => None
      }
      case _ => None
    }
  }

  def registerParameterValues(parameterValues: Map[_ <: ScParameter, DfaValue],
                              interpreter: DataFlowInterpreter, newState: DfaMemoryState)
                             (implicit factory: DfaValueFactory): Unit = {
    parameterValues.foreach { case (parameter, value) =>
      val dfaVariable = factory.getVarFactory.createVariableValue(ScalaDfaVariableDescriptor(parameter, parameter.isStable))
      newState.push(value)
      val assignment = new SimpleAssignmentInstruction(null, dfaVariable)
      assignment.accept(interpreter, newState)
      newState.pop()
    }
  }

  private def supportsInterproceduralAnalysis(function: ScFunctionDefinition, invocationInfo: InvocationInfo,
                                              currentAnalysedMethodInfo: AnalysedMethodInfo): Boolean = {
    val isInsideFinalClassOrObject = hasFinalOrPrivateModifier(function.containingClass) || function.containingClass.is[ScObject]
    val isEffectivelyFinal = hasFinalOrPrivateModifier(function) || isInsideFinalClassOrObject
    val containsUnsupportedFeatures = implicitParametersPresent(invocationInfo) || byNameParametersPresent(invocationInfo)
    val isRecursionOrToDeep = function == currentAnalysedMethodInfo.method ||
      currentAnalysedMethodInfo.invocationDepth + 1 > InterproceduralAnalysisDepthLimit

    isEffectivelyFinal && !containsUnsupportedFeatures && !isRecursionOrToDeep && !function.isSynthetic
  }

  private def hasFinalOrPrivateModifier(element: PsiModifierListOwner): Boolean = {
    Option(element).exists(_.hasModifierPropertyScala(PsiModifier.FINAL)) ||
      Option(element).exists(_.hasModifierPropertyScala(PsiModifier.PRIVATE))
  }

  private def mapArgumentValuesToParams(invocationInfo: InvocationInfo, function: ScFunctionDefinition,
                                        argumentValues: Map[Argument, DfaValue])
                                       (implicit factory: DfaValueFactory): Map[ScParameter, DfaValue] = {
    val argumentVector = invocationInfo.properArguments.flatten.toVector
    function.parameters.zip(invocationInfo.paramToProperArgMapping).map {
      case (param, argMapping) =>
        val argValue = argMapping.flatMap(index => argumentValues.get(argumentVector(index)))
          .getOrElse(unknownDfaValue)
        param -> argValue
    }.toMap
  }

  private def analyseExternalMethodBody(method: ScFunctionDefinition, body: ScExpression,
                                        mappedParameters: Map[ScParameter, DfaValue],
                                        currentAnalysedMethodInfo: AnalysedMethodInfo)
                                       (implicit factory: DfaValueFactory): Option[DfType] = {
    val newAnalysedInfo = AnalysedMethodInfo(method, currentAnalysedMethodInfo.invocationDepth + 1)
    val controlFlowBuilder = new ScalaDfaControlFlowBuilder(newAnalysedInfo, factory, body)
    new ScalaPsiElementTransformer(body).transform(controlFlowBuilder)

    val resultDestination = factory.getVarFactory.createVariableValue(MethodResultDescriptor(method))
    val flow = controlFlowBuilder.buildAndReturn(resultDestination)

    val listener = new MethodResultDfaListener(resultDestination)
    val interpreter = new StandardDataFlowInterpreter(flow, listener)

    val startingState = new JvmDfaMemoryStateImpl(factory)
    registerParameterValues(mappedParameters, interpreter, startingState)

    if (interpreter.interpret(startingState) != RunnerResult.OK) None
    else Some(listener.resultValue)
  }
}
