/*
 * Copyright 2011-2019 Asakusa Framework Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.asakusafw.spark.extensions.iterativebatch.compiler
package graph

import scala.collection.JavaConversions._

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.model.graph.UserOperator
import com.asakusafw.lang.compiler.planning.SubPlan
import com.asakusafw.spark.compiler.`package`._
import com.asakusafw.spark.compiler.graph.{
  AggregateClassBuilder,
  AggregateInstantiator,
  CacheOnce,
  Instantiator
}
import com.asakusafw.spark.compiler.planning.{ IterativeInfo, SubPlanInfo }
import com.asakusafw.spark.compiler.spi.NodeCompiler

import com.asakusafw.spark.extensions.iterativebatch.compiler.spi.RoundAwareNodeCompiler

class AggregateCompiler extends RoundAwareNodeCompiler {

  override def support(
    subplan: SubPlan)(
      implicit context: NodeCompiler.Context): Boolean = {
    subplan.getAttribute(classOf[SubPlanInfo]).getDriverType == SubPlanInfo.DriverType.AGGREGATE
  }

  override def instantiator: Instantiator = AggregateInstantiator

  override def compile(
    subplan: SubPlan)(
      implicit context: NodeCompiler.Context): Type = {
    assert(support(subplan), s"The subplan is not supported: ${subplan}")

    val subPlanInfo = subplan.getAttribute(classOf[SubPlanInfo])
    val primaryOperator = subPlanInfo.getPrimaryOperator
    assert(primaryOperator.isInstanceOf[UserOperator],
      s"The primary operator should be user operator: ${primaryOperator}")
    val operator = primaryOperator.asInstanceOf[UserOperator]

    val iterativeInfo = IterativeInfo.get(subplan)

    val valueType = operator.inputs.head.dataModelType
    val combinerType = operator.outputs.head.dataModelType

    val builder =
      iterativeInfo.getRecomputeKind match {
        case IterativeInfo.RecomputeKind.ALWAYS =>
          new AggregateClassBuilder(
            valueType,
            combinerType,
            operator,
            mapSideCombine =
              subPlanInfo.getDriverOptions.contains(SubPlanInfo.DriverOption.PARTIAL))(
            subplan.label,
            subplan.getOutputs.toSeq) with CacheAlways
        case IterativeInfo.RecomputeKind.PARAMETER =>
          new AggregateClassBuilder(
            valueType,
            combinerType,
            operator,
            mapSideCombine =
              subPlanInfo.getDriverOptions.contains(SubPlanInfo.DriverOption.PARTIAL))(
            subplan.label,
            subplan.getOutputs.toSeq) with CacheByParameter {

            override val parameters: Set[String] = iterativeInfo.getParameters.toSet
          }
        case IterativeInfo.RecomputeKind.NEVER =>
          new AggregateClassBuilder(
            valueType,
            combinerType,
            operator,
            mapSideCombine =
              subPlanInfo.getDriverOptions.contains(SubPlanInfo.DriverOption.PARTIAL))(
            subplan.label,
            subplan.getOutputs.toSeq) with CacheOnce
      }

    context.addClass(builder)
  }
}
