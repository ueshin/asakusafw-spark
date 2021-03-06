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
package iterative

import scala.collection.JavaConversions._
import scala.collection.mutable

import org.objectweb.asm.Type

import com.asakusafw.iterative.common.IterativeExtensions
import com.asakusafw.lang.compiler.api.{ CompilerOptions, DataModelLoader }
import com.asakusafw.lang.compiler.api.JobflowProcessor.{ Context => JPContext }
import com.asakusafw.lang.compiler.api.reference.{
  CommandToken,
  ExternalInputReference,
  TaskReference
}
import com.asakusafw.lang.compiler.common.Location
import com.asakusafw.lang.compiler.hadoop.{
  HadoopCommandRequired,
  InputFormatInfo,
  InputFormatInfoExtension
}
import com.asakusafw.lang.compiler.model.description.ClassDescription
import com.asakusafw.lang.compiler.model.info.{ ExternalInputInfo, ExternalOutputInfo }
import com.asakusafw.lang.compiler.planning.Plan
import com.asakusafw.spark.compiler._
import com.asakusafw.spark.compiler.graph.{
  BranchKeysClassBuilder,
  BroadcastIdsClassBuilder,
  Instantiator
}
import com.asakusafw.spark.compiler.planning.IterativeInfo
import com.asakusafw.spark.compiler.spi.{
  AggregationCompiler,
  ExtensionCompiler,
  NodeCompiler,
  OperatorCompiler
}
import com.asakusafw.spark.tools.asm.ClassBuilder

import com.asakusafw.spark.extensions.iterativebatch.compiler.graph.IterativeJobCompiler

import resource._

class IterativeBatchExtensionCompiler extends ExtensionCompiler {

  override def support(plan: Plan)(jpContext: JPContext): Boolean = {
    IterativeInfo.isIterative(plan)
  }

  override def compile(plan: Plan)(flowId: String, jpContext: JPContext): Unit = {

    implicit val context: IterativeBatchExtensionCompiler.Context =
      new IterativeBatchExtensionCompiler.DefaultContext(flowId)(jpContext)

    val builder = new IterativeBatchSparkClientClassBuilder(plan)
    val client = context.addClass(builder)

    val task = context.addTask(
      SparkClientCompiler.ModuleName,
      SparkClientCompiler.ProfileName,
      SparkClientCompiler.Command,
      Seq(
        CommandToken.BATCH_ID,
        CommandToken.FLOW_ID,
        CommandToken.EXECUTION_ID,
        CommandToken.BATCH_ARGUMENTS,
        CommandToken.of(client.getClassName)),
      Seq(IterativeExtensions.EXTENSION_NAME))
    HadoopCommandRequired.put(task, false)
  }
}

object IterativeBatchExtensionCompiler {

  trait Context
    extends CompilerContext
    with ClassLoaderProvider
    with DataModelLoaderProvider {

    def addTask(
      moduleName: String,
      profileName: String,
      command: Location,
      arguments: Seq[CommandToken],
      extensions: Seq[String],
      blockers: TaskReference*): TaskReference

    def branchKeys: BranchKeysClassBuilder
    def broadcastIds: BroadcastIdsClassBuilder

    def iterativeJobCompilerContext: IterativeJobCompiler.Context
  }

  class DefaultContext(val flowId: String)(jpContext: JPContext)
    extends Context
    with IterativeJobCompiler.Context
    with NodeCompiler.Context
    with Instantiator.Context
    with OperatorCompiler.Context
    with AggregationCompiler.Context {

    override def iterativeJobCompilerContext: IterativeJobCompiler.Context = this
    override def nodeCompilerContext: NodeCompiler.Context = this
    override def instantiatorCompilerContext: Instantiator.Context = this
    override def operatorCompilerContext: OperatorCompiler.Context = this
    override def aggregationCompilerContext: AggregationCompiler.Context = this

    override def classLoader: ClassLoader = jpContext.getClassLoader
    override def dataModelLoader: DataModelLoader = jpContext.getDataModelLoader
    override def options: CompilerOptions = jpContext.getOptions

    override val branchKeys: BranchKeysClassBuilder = new BranchKeysClassBuilder(flowId)
    override val broadcastIds: BroadcastIdsClassBuilder = new BroadcastIdsClassBuilder(flowId)

    override def getInputFormatInfo(
      name: String, info: ExternalInputInfo): Option[InputFormatInfo] = {
      Option(InputFormatInfoExtension.resolve(jpContext, name, info))
    }

    private val externalInputs: mutable.Map[String, ExternalInputReference] = mutable.Map.empty

    override def addExternalInput(
      name: String, info: ExternalInputInfo): ExternalInputReference = {
      externalInputs.getOrElseUpdate(
        name,
        jpContext.addExternalInput(name, info))
    }

    private val externalOutputs: mutable.Map[String, Unit] = mutable.Map.empty

    override def addExternalOutput(
      name: String, info: ExternalOutputInfo, paths: Seq[String]): Unit = {
      externalOutputs.getOrElseUpdate(
        name,
        jpContext.addExternalOutput(name, info, paths))
    }

    override def addClass(builder: ClassBuilder): Type = {
      for {
        os <- managed(jpContext.addClassFile(new ClassDescription(builder.thisType.getClassName)))
      } {
        os.write(builder.build())
      }
      builder.thisType
    }

    override def addTask(
      moduleName: String,
      profileName: String,
      command: Location,
      arguments: Seq[CommandToken],
      extensions: Seq[String],
      blockers: TaskReference*): TaskReference = {
      jpContext.addTask(moduleName, profileName, command, arguments, extensions, blockers: _*)
    }
  }
}
