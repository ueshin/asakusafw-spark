package com.asakusafw.spark.compiler
package operator
package user
package join

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.model.graph.UserOperator
import com.asakusafw.spark.compiler.spi.OperatorType
import com.asakusafw.vocabulary.operator.{ MasterBranch => MasterBranchOp }

class ShuffledMasterBranchOperatorCompiler extends UserOperatorCompiler {

  override def support(operator: UserOperator)(implicit context: Context): Boolean = {
    val operatorInfo = new OperatorInfo(operator)(context.jpContext)
    import operatorInfo._
    annotationDesc.resolveClass == classOf[MasterBranchOp]
  }

  override def operatorType: OperatorType = OperatorType.CoGroupType

  override def compile(operator: UserOperator)(implicit context: Context): Type = {
    assert(support(operator))

    val operatorInfo = new OperatorInfo(operator)(context.jpContext)
    import operatorInfo._

    assert(inputs.size >= 2)
    assert(outputs.size > 0)

    outputs.foreach { output =>
      assert(output.dataModelType == inputs(MasterBranchOp.ID_INPUT_TRANSACTION).dataModelType)
    }

    methodDesc.parameterClasses
      .zip(inputs(MasterBranchOp.ID_INPUT_MASTER).dataModelClass
        +: inputs(MasterBranchOp.ID_INPUT_TRANSACTION).dataModelClass
        +: arguments.map(_.resolveClass))
      .foreach {
        case (method, model) => assert(method.isAssignableFrom(model))
      }

    val builder = new JoinOperatorFragmentClassBuilder(
      context.flowId,
      implementationClassType,
      outputs) with ShuffledJoin with MasterBranch {

      val masterType: Type = inputs(MasterBranchOp.ID_INPUT_MASTER).dataModelType
      val txType: Type = inputs(MasterBranchOp.ID_INPUT_TRANSACTION).dataModelType
      val masterSelection: Option[(String, Type)] = selectionMethod

      val opInfo: OperatorInfo = operatorInfo
    }

    context.jpContext.addClass(builder)
  }
}
