package com.asakusafw.spark.compiler
package operator
package user
package join

import java.util.{ List => JList }

import scala.collection.JavaConversions

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.model.graph.OperatorOutput
import com.asakusafw.spark.runtime.operator.DefaultMasterSelection
import com.asakusafw.spark.tools.asm._
import com.asakusafw.spark.tools.asm.MethodBuilder._

abstract class JoinOperatorFragmentClassBuilder(
  flowId: String,
  dataModelType: Type,
  operatorType: Type,
  operatorOutputs: Seq[OperatorOutput])
    extends UserOperatorFragmentClassBuilder(
      flowId, dataModelType, operatorType, operatorOutputs) {

  def masterType: Type
  def txType: Type
  def masterSelection: Option[(String, Type)]

  def join(mb: MethodBuilder, masterVar: Var, txVar: Var): Unit
}
