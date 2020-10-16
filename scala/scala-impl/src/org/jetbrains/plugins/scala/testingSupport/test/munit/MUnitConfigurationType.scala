package org.jetbrains.plugins.scala.testingSupport.test.munit

import com.intellij.execution.configurations.{ConfigurationFactory, ConfigurationTypeUtil}
import com.intellij.execution.junit.JUnitConfigurationType
import javax.swing.Icon
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.plugins.scala.icons.Icons

@ApiStatus.Experimental
final class MUnitConfigurationType extends JUnitConfigurationType {

  val confFactory = new MUnitConfigurationFactory(this)

  override def getId: String = "MUnitRunConfiguration"

  override def getDisplayName: String = "MUnit"

  override def getConfigurationTypeDescription: String = "MUnit testing framework run configuration"

  override def getHelpTopic: String = super.getHelpTopic

  override def getIcon: Icon = Icons.SCALA_TEST

  override def getConfigurationFactories: Array[ConfigurationFactory] = Array(confFactory)
}

object MUnitConfigurationType {

  def apply(): MUnitConfigurationType =
    ConfigurationTypeUtil.findConfigurationType(classOf[MUnitConfigurationType])
}

