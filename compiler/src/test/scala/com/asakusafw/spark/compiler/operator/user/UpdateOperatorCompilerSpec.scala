/*
 * Copyright 2011-2015 Asakusa Framework Team.
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
package com.asakusafw.spark.compiler
package operator
package user

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import java.io.{ DataInput, DataOutput }

import scala.collection.JavaConversions._

import org.apache.hadoop.io.Writable
import org.apache.spark.broadcast.Broadcast

import com.asakusafw.lang.compiler.model.description.{ ClassDescription, ImmediateDescription }
import com.asakusafw.lang.compiler.model.testing.OperatorExtractor
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.runtime.value.{ IntOption, LongOption }
import com.asakusafw.spark.compiler.spi.{ OperatorCompiler, OperatorType }
import com.asakusafw.spark.runtime.driver.BroadcastId
import com.asakusafw.spark.runtime.fragment.{ Fragment, GenericOutputFragment }
import com.asakusafw.spark.tools.asm._
import com.asakusafw.vocabulary.operator.Update

@RunWith(classOf[JUnitRunner])
class UpdateOperatorCompilerSpecTest extends UpdateOperatorCompilerSpec

class UpdateOperatorCompilerSpec extends FlatSpec with UsingCompilerContext {

  import UpdateOperatorCompilerSpec._

  behavior of classOf[UpdateOperatorCompiler].getSimpleName

  it should "compile Update operator" in {
    val operator = OperatorExtractor.extract(
      classOf[Update], classOf[UpdateOperator], "update")
      .input("in", ClassDescription.of(classOf[Foo]))
      .output("out", ClassDescription.of(classOf[Foo]))
      .argument("rate", ImmediateDescription.of(100))
      .build();

    implicit val context = newOperatorCompilerContext("flowId")

    val thisType = OperatorCompiler.compile(operator, OperatorType.ExtractType)
    val cls = context.loadClass[Fragment[Foo]](thisType.getClassName)

    val out = new GenericOutputFragment[Foo]()

    val fragment = cls
      .getConstructor(classOf[Map[BroadcastId, Broadcast[_]]], classOf[Fragment[_]])
      .newInstance(Map.empty, out)

    fragment.reset()
    val foo = new Foo()
    for (i <- 0 until 10) {
      foo.reset()
      foo.i.modify(i)
      fragment.add(foo)
    }
    out.iterator.zipWithIndex.foreach {
      case (foo, i) =>
        assert(foo.i.get === i)
        assert(foo.l.get === i * 100)
    }

    fragment.reset()
  }

  it should "compile Update operator with projective model" in {
    val operator = OperatorExtractor.extract(
      classOf[Update], classOf[UpdateOperator], "updatep")
      .input("in", ClassDescription.of(classOf[Foo]))
      .output("out", ClassDescription.of(classOf[Foo]))
      .argument("rate", ImmediateDescription.of(100))
      .build();

    implicit val context = newOperatorCompilerContext("flowId")

    val thisType = OperatorCompiler.compile(operator, OperatorType.ExtractType)
    val cls = context.loadClass[Fragment[Foo]](thisType.getClassName)

    val out = new GenericOutputFragment[Foo]()

    val fragment = cls
      .getConstructor(classOf[Map[BroadcastId, Broadcast[_]]], classOf[Fragment[_]])
      .newInstance(Map.empty, out)

    fragment.reset()
    val foo = new Foo()
    for (i <- 0 until 10) {
      foo.reset()
      foo.i.modify(i)
      fragment.add(foo)
    }
    out.iterator.zipWithIndex.foreach {
      case (foo, i) =>
        assert(foo.i.get === i)
        assert(foo.l.get === i * 100)
    }

    fragment.reset()
  }
}

object UpdateOperatorCompilerSpec {

  trait FooP {
    def getIOption: IntOption
    def getLOption: LongOption
  }

  class Foo extends DataModel[Foo] with FooP with Writable {

    val i: IntOption = new IntOption()
    val l: LongOption = new LongOption()

    override def reset: Unit = {
      i.setNull()
      l.setNull()
    }
    override def copyFrom(other: Foo): Unit = {
      i.copyFrom(other.i)
      l.copyFrom(other.l)
    }
    override def readFields(in: DataInput): Unit = {
      i.readFields(in)
      l.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      i.write(out)
      l.write(out)
    }

    def getIOption: IntOption = i
    def getLOption: LongOption = l
  }

  class UpdateOperator {

    @Update
    def update(foo: Foo, rate: Int): Unit = {
      foo.l.modify(rate * foo.i.get)
    }

    @Update
    def updatep[F <: FooP](foo: F, rate: Int): Unit = {
      foo.getLOption.modify(rate * foo.getIOption.get)
    }
  }
}
