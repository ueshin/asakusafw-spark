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
import java.util.{ List => JList }

import scala.collection.JavaConversions._
import scala.collection.mutable

import org.apache.hadoop.io.Writable
import org.apache.spark.broadcast.Broadcast

import com.asakusafw.lang.compiler.api.CompilerOptions
import com.asakusafw.lang.compiler.api.testing.MockJobflowProcessorContext
import com.asakusafw.lang.compiler.model.PropertyName
import com.asakusafw.lang.compiler.model.description._
import com.asakusafw.lang.compiler.model.graph.Groups
import com.asakusafw.lang.compiler.model.testing.OperatorExtractor
import com.asakusafw.runtime.core.Result
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.runtime.value._
import com.asakusafw.spark.compiler.spi.{ OperatorCompiler, OperatorType }
import com.asakusafw.spark.compiler.subplan.{ BranchKeysClassBuilder, BroadcastIdsClassBuilder }
import com.asakusafw.spark.runtime.driver.BroadcastId
import com.asakusafw.spark.runtime.fragment._
import com.asakusafw.spark.runtime.operator.GenericOutputFragment
import com.asakusafw.spark.tools.asm._
import com.asakusafw.vocabulary.operator.CoGroup

@RunWith(classOf[JUnitRunner])
class CoGroupOperatorCompilerSpecTest extends CoGroupOperatorCompilerSpec

class CoGroupOperatorCompilerSpec extends FlatSpec with UsingCompilerContext {

  import CoGroupOperatorCompilerSpec._

  behavior of classOf[CoGroupOperatorCompiler].getSimpleName

  it should "compile CoGroup operator" in {
    val operator = OperatorExtractor
      .extract(classOf[CoGroup], classOf[CoGroupOperator], "cogroup")
      .input("fooList", ClassDescription.of(classOf[Foo]),
        Groups.parse(Seq("id")))
      .input("barList", ClassDescription.of(classOf[Bar]),
        Groups.parse(Seq("fooId"), Seq("+id")))
      .output("fooResult", ClassDescription.of(classOf[Foo]))
      .output("barResult", ClassDescription.of(classOf[Bar]))
      .output("fooError", ClassDescription.of(classOf[Foo]))
      .output("barError", ClassDescription.of(classOf[Bar]))
      .output("nResult", ClassDescription.of(classOf[N]))
      .argument("n", ImmediateDescription.of(10))
      .build()

    implicit val context = newOperatorCompilerContext("flowId")

    val thisType = OperatorCompiler.compile(operator, OperatorType.CoGroupType)
    val cls = context.loadClass[Fragment[Seq[Iterator[_]]]](thisType.getClassName)

    val fooResult = new GenericOutputFragment[Foo]()
    val fooError = new GenericOutputFragment[Foo]()

    val barResult = new GenericOutputFragment[Bar]()
    val barError = new GenericOutputFragment[Bar]()

    val nResult = new GenericOutputFragment[N]()

    val fragment = cls.getConstructor(
      classOf[Map[BroadcastId, Broadcast[_]]],
      classOf[Fragment[_]], classOf[Fragment[_]],
      classOf[Fragment[_]], classOf[Fragment[_]],
      classOf[Fragment[_]])
      .newInstance(Map.empty, fooResult, barResult, fooError, barError, nResult)

    {
      fragment.reset()
      val foos = Seq.empty[Foo]
      val bars = Seq.empty[Bar]
      fragment.add(Seq(foos.iterator, bars.iterator))
      assert(fooResult.iterator.size === 0)
      assert(barResult.iterator.size === 0)
      assert(fooError.iterator.size === 0)
      assert(barError.iterator.size === 0)
      val nResults = nResult.iterator.toSeq
      assert(nResults.size === 1)
      assert(nResults.head.n.get === 10)
    }

    fragment.reset()
    assert(fooResult.iterator.size === 0)
    assert(barResult.iterator.size === 0)
    assert(fooError.iterator.size === 0)
    assert(barError.iterator.size === 0)
    assert(nResult.iterator.size === 0)

    {
      fragment.reset()
      val foo = new Foo()
      foo.id.modify(1)
      val foos = Seq(foo)
      val bar = new Bar()
      bar.id.modify(10)
      bar.fooId.modify(1)
      val bars = Seq(bar)
      fragment.add(Seq(foos.iterator, bars.iterator))
      val fooResults = fooResult.iterator.toSeq
      assert(fooResults.size === 1)
      assert(fooResults.head.id.get === foo.id.get)
      val barResults = barResult.iterator.toSeq
      assert(barResults.size === 1)
      assert(barResults.head.id.get === bar.id.get)
      assert(barResults.head.fooId.get === bar.fooId.get)
      val fooErrors = fooError.iterator.toSeq
      assert(fooErrors.size === 0)
      val barErrors = barError.iterator.toSeq
      assert(barErrors.size === 0)
      val nResults = nResult.iterator.toSeq
      assert(nResults.size === 1)
      assert(nResults.head.n.get === 10)
    }

    fragment.reset()
    assert(fooResult.iterator.size === 0)
    assert(barResult.iterator.size === 0)
    assert(fooError.iterator.size === 0)
    assert(barError.iterator.size === 0)
    assert(nResult.iterator.size === 0)

    {
      fragment.reset()
      val foo = new Foo()
      foo.id.modify(1)
      val foos = Seq(foo)
      val bars = (0 until 10).map { i =>
        val bar = new Bar()
        bar.id.modify(i)
        bar.fooId.modify(1)
        bar
      }
      fragment.add(Seq(foos.iterator, bars.iterator))
      val fooResults = fooResult.iterator.toSeq
      assert(fooResults.size === 0)
      val barResults = barResult.iterator.toSeq
      assert(barResults.size === 0)
      val fooErrors = fooError.iterator.toSeq
      assert(fooErrors.size === 1)
      assert(fooErrors.head.id.get === foo.id.get)
      val barErrors = barError.iterator.toSeq
      assert(barErrors.size === 10)
      barErrors.zip(bars).foreach {
        case (actual, expected) =>
          assert(actual.id.get === expected.id.get)
          assert(actual.fooId.get === expected.fooId.get)
      }
      val nResults = nResult.iterator.toSeq
      assert(nResults.size === 1)
      assert(nResults.head.n.get === 10)
    }

    fragment.reset()
    assert(fooResult.iterator.size === 0)
    assert(barResult.iterator.size === 0)
    assert(fooError.iterator.size === 0)
    assert(barError.iterator.size === 0)
    assert(nResult.iterator.size === 0)
  }
}

object CoGroupOperatorCompilerSpec {

  trait FooP {
    def getIdOption: IntOption
  }

  class Foo extends DataModel[Foo] with FooP with Writable {

    val id = new IntOption()

    override def reset(): Unit = {
      id.setNull()
    }
    override def copyFrom(other: Foo): Unit = {
      id.copyFrom(other.id)
    }
    override def readFields(in: DataInput): Unit = {
      id.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      id.write(out)
    }

    def getIdOption: IntOption = id
  }

  trait BarP {
    def getIdOption: IntOption
    def getFooIdOption: IntOption
  }

  class Bar extends DataModel[Bar] with BarP with Writable {

    val id = new IntOption()
    val fooId = new IntOption()

    override def reset(): Unit = {
      id.setNull()
      fooId.setNull()
    }
    override def copyFrom(other: Bar): Unit = {
      id.copyFrom(other.id)
      fooId.copyFrom(other.fooId)
    }
    override def readFields(in: DataInput): Unit = {
      id.readFields(in)
      fooId.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      id.write(out)
      fooId.write(out)
    }

    def getIdOption: IntOption = id
    def getFooIdOption: IntOption = fooId
  }

  class N extends DataModel[N] with Writable {

    val n = new IntOption()

    override def reset(): Unit = {
      n.setNull()
    }
    override def copyFrom(other: N): Unit = {
      n.copyFrom(other.n)
    }
    override def readFields(in: DataInput): Unit = {
      n.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      n.write(out)
    }

    def getIOption: IntOption = n
  }

  class CoGroupOperator {

    private[this] val n = new N

    @CoGroup
    def cogroup(
      fooList: JList[Foo], barList: JList[Bar],
      fooResult: Result[Foo], barResult: Result[Bar],
      fooError: Result[Foo], barError: Result[Bar],
      nResult: Result[N], n: Int): Unit = {
      if (fooList.size == 1 && barList.size == 1) {
        fooResult.add(fooList(0))
        barResult.add(barList(0))
      } else {
        fooList.foreach(fooError.add)
        barList.foreach(barError.add)
      }
      this.n.n.modify(n)
      nResult.add(this.n)
    }

    @CoGroup
    def cogroupp[F <: FooP, B <: BarP](
      fooList: JList[F], barList: JList[B],
      fooResult: Result[F], barResult: Result[B],
      fooError: Result[F], barError: Result[B],
      nResult: Result[N], n: Int): Unit = {
      if (fooList.size == 1 && barList.size == 1) {
        fooResult.add(fooList(0))
        barResult.add(barList(0))
      } else {
        fooList.foreach(fooError.add)
        barList.foreach(barError.add)
      }
      this.n.n.modify(n)
      nResult.add(this.n)
    }
  }
}
