package com.asakusafw.spark.compiler
package operator
package user
package join

import java.util.{ List => JList }

import scala.collection.JavaConversions
import scala.collection.JavaConversions._
import scala.reflect.ClassTag

import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureVisitor

import com.asakusafw.lang.compiler.analyzer.util.{ JoinedModelUtil, MasterJoinOperatorUtil }
import com.asakusafw.lang.compiler.model.graph.{ OperatorOutput, UserOperator }
import com.asakusafw.runtime.core.Result
import com.asakusafw.spark.compiler.operator.FragmentClassBuilder
import com.asakusafw.spark.compiler.spi.UserOperatorCompiler
import com.asakusafw.spark.runtime.fragment.Fragment
import com.asakusafw.spark.runtime.operator.DefaultMasterSelection
import com.asakusafw.spark.runtime.util.ValueOptionOps
import com.asakusafw.spark.tools.asm._
import com.asakusafw.vocabulary.operator.MasterJoin

class MasterJoinOperatorCompiler extends UserOperatorCompiler {

  override def of: Class[_] = classOf[MasterJoin]

  override def compile(operator: UserOperator)(implicit context: Context): Type = {
    val annotationDesc = operator.getAnnotation
    assert(annotationDesc.getDeclaringClass.resolve(context.jpContext.getClassLoader) == of)
    val implementationClassType = operator.getImplementationClass.asType

    val selectionMethod =
      Option(MasterJoinOperatorUtil.getSelection(context.jpContext.getClassLoader, operator))
        .map(method => (method.getName, Type.getType(method)))

    val inputs = operator.getInputs.toSeq
    assert(inputs.size >= 2)
    val inputDataModelRefs = inputs.map(input => context.jpContext.getDataModelLoader.load(input.getDataType))
    val inputDataModelTypes = inputDataModelRefs.map(_.getDeclaration.asType)

    val masterDataModelType = inputDataModelTypes(MasterJoin.ID_INPUT_MASTER)
    val txDataModelType = inputDataModelTypes(MasterJoin.ID_INPUT_TRANSACTION)

    val outputs = operator.getOutputs.toSeq
    assert(outputs.size == 2)
    val outputDataModelRefs = outputs.map(output => context.jpContext.getDataModelLoader.load(output.getDataType))
    val outputDataModelTypes = outputDataModelRefs.map(_.getDeclaration.asType)

    val joinedOutput = outputs(MasterJoin.ID_OUTPUT_JOINED)
    val joinedOutputDataModelRef = context.jpContext.getDataModelLoader.load(joinedOutput.getDataType)
    val joinedOutputDataModelType = joinedOutputDataModelRef.getDeclaration.asType

    val missedOutput = outputs(MasterJoin.ID_OUTPUT_MISSED)
    val missedOutputDataModelRef = context.jpContext.getDataModelLoader.load(missedOutput.getDataType)
    val missedOutputDataModelType = missedOutputDataModelRef.getDeclaration.asType

    assert(missedOutputDataModelType == txDataModelType)

    val mappings = JoinedModelUtil.getPropertyMappings(context.jpContext.getClassLoader, operator).toSeq

    val builder = new FragmentClassBuilder(
      context.flowId,
      classOf[Seq[Iterable[_]]].asType) with OperatorField with OutputFragments {

      override def operatorType: Type = implementationClassType
      override def operatorOutputs: Seq[OperatorOutput] = outputs

      override def defFields(fieldDef: FieldDef): Unit = {
        fieldDef.newField("joinedDataModel", joinedOutputDataModelType)

        defOperatorField(fieldDef)
        defOutputFields(fieldDef)
      }

      override def defConstructors(ctorDef: ConstructorDef): Unit = {
        ctorDef.newInit((0 until outputs.size).map(_ => classOf[Fragment[_]].asType),
          ((new MethodSignatureBuilder() /: outputs) {
            case (builder, output) =>
              builder.newParameterType {
                _.newClassType(classOf[Fragment[_]].asType) {
                  _.newTypeArgument(SignatureVisitor.INSTANCEOF, output.getDataType.asType)
                }
              }
          })
            .newVoidReturnType()
            .build()) { mb =>
            import mb._
            thisVar.push().invokeInit(superType)
            thisVar.push().putField("joinedDataModel", joinedOutputDataModelType, pushNew0(joinedOutputDataModelType))
            initOutputFields(mb, thisVar.nextLocal)
          }
      }

      override def defMethods(methodDef: MethodDef): Unit = {
        super.defMethods(methodDef)

        methodDef.newMethod("add", Seq(classOf[Seq[Iterable[_]]].asType),
          new MethodSignatureBuilder()
            .newParameterType {
              _.newClassType(classOf[Seq[_]].asType) {
                _.newTypeArgument(SignatureVisitor.INSTANCEOF) {
                  _.newClassType(classOf[Iterable[_]].asType) {
                    _.newTypeArgument(SignatureVisitor.INSTANCEOF, classOf[AnyRef].asType)
                  }
                }
              }
            }
            .newVoidReturnType()
            .build()) { mb =>
            import mb._
            val groupsVar = `var`(classOf[Seq[Iterable[_]]].asType, thisVar.nextLocal)
            val mastersVar = getStatic(JavaConversions.getClass.asType, "MODULE$", JavaConversions.getClass.asType)
              .invokeV("seqAsJavaList", classOf[JList[_]].asType,
                groupsVar.push().invokeI(
                  "apply", classOf[AnyRef].asType, ldc(0).box().asType(classOf[AnyRef].asType))
                  .cast(classOf[Iterable[_]].asType)
                  .invokeI("toSeq", classOf[Seq[_]].asType))
              .store(groupsVar.nextLocal)
            val txIterVar = groupsVar.push().invokeI(
              "apply", classOf[AnyRef].asType, ldc(1).box().asType(classOf[AnyRef].asType))
              .cast(classOf[Iterable[_]].asType)
              .invokeI("iterator", classOf[Iterator[_]].asType)
              .store(mastersVar.nextLocal)
            loop { ctrl =>
              txIterVar.push().invokeI("hasNext", Type.BOOLEAN_TYPE).unlessTrue(ctrl.break())
              val txVar = txIterVar.push().invokeI("next", classOf[AnyRef].asType)
                .cast(txDataModelType).store(txIterVar.nextLocal)
              val selected = selectionMethod match {
                case Some((name, t)) =>
                  getOperatorField(mb).invokeV(name, t.getReturnType(), mastersVar.push(), txVar.push())
                case None =>
                  getStatic(DefaultMasterSelection.getClass.asType, "MODULE$", DefaultMasterSelection.getClass.asType)
                    .invokeV("select", classOf[AnyRef].asType, mastersVar.push(), txVar.push().asType(classOf[AnyRef].asType))
                    .cast(masterDataModelType)
              }
              selected.dup().unlessNotNull {
                selected.pop()
                getOutputField(mb, missedOutput)
                  .invokeV("add", txVar.push().asType(classOf[AnyRef].asType))
                ctrl.continue()
              }
              val selectedVar = selected.store(txVar.nextLocal)

              val vars = Seq(selectedVar, txVar)

              thisVar.push().getField("joinedDataModel", joinedOutputDataModelType).invokeV("reset")

              mappings.foreach { mapping =>
                val srcVar = vars(inputs.indexOf(mapping.getSourcePort))
                val srcProperty = inputDataModelRefs(inputs.indexOf(mapping.getSourcePort))
                  .findProperty(mapping.getSourceProperty)
                val destProperty = joinedOutputDataModelRef.findProperty(mapping.getDestinationProperty)
                assert(srcProperty.getType.asType == destProperty.getType.asType)

                getStatic(ValueOptionOps.getClass.asType, "MODULE$", ValueOptionOps.getClass.asType)
                  .invokeV(
                    "copy",
                    srcVar.push()
                      .invokeV(srcProperty.getDeclaration.getName, srcProperty.getType.asType),
                    thisVar.push().getField("joinedDataModel", joinedOutputDataModelType)
                      .invokeV(destProperty.getDeclaration.getName, destProperty.getType.asType))
              }

              getOutputField(mb, joinedOutput)
                .invokeV("add", thisVar.push().getField("joinedDataModel", joinedOutputDataModelType).asType(classOf[AnyRef].asType))
            }
            `return`()
          }

        methodDef.newMethod("reset", Seq.empty) { mb =>
          import mb._
          resetOutputs(mb)
          `return`()
        }

        defGetOperator(methodDef)
      }
    }

    context.jpContext.addClass(builder)
  }
}
