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
package join

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import java.io.{ DataInput, DataOutput }
import java.util.{ List => JList }

import scala.collection.JavaConversions._

import org.apache.hadoop.io.Writable
import org.apache.spark.broadcast.Broadcast

import com.asakusafw.lang.compiler.model.description.ClassDescription
import com.asakusafw.lang.compiler.model.graph.{ Groups, MarkerOperator }
import com.asakusafw.lang.compiler.model.testing.OperatorExtractor
import com.asakusafw.lang.compiler.planning.PlanMarker
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.runtime.value.IntOption
import com.asakusafw.spark.compiler.broadcast.MockBroadcast
import com.asakusafw.spark.compiler.spi.{ OperatorCompiler, OperatorType }
import com.asakusafw.spark.runtime.driver.{ BroadcastId, ShuffleKey }
import com.asakusafw.spark.runtime.fragment.{ Fragment, GenericOutputFragment }
import com.asakusafw.spark.runtime.io.WritableSerDe
import com.asakusafw.spark.tools.asm._
import com.asakusafw.vocabulary.operator.{ MasterBranch => MasterBranchOp, MasterSelection }

@RunWith(classOf[JUnitRunner])
class BroadcastMasterBranchOperatorCompilerSpecTest extends BroadcastMasterBranchOperatorCompilerSpec

class BroadcastMasterBranchOperatorCompilerSpec extends FlatSpec with UsingCompilerContext {

  import BroadcastMasterBranchOperatorCompilerSpec._

  behavior of classOf[BroadcastMasterBranchOperatorCompiler].getSimpleName

  it should "compile MasterBranch operator without master selection" in {
    val foosMarker = MarkerOperator.builder(ClassDescription.of(classOf[Foo]))
      .attribute(classOf[PlanMarker], PlanMarker.BROADCAST).build()

    val operator = OperatorExtractor
      .extract(classOf[MasterBranchOp], classOf[MasterBranchOperator], "branch")
      .input("foos", ClassDescription.of(classOf[Foo]),
        Groups.parse(Seq("id")), foosMarker.getOutput)
      .input("bars", ClassDescription.of(classOf[Bar]),
        Groups.parse(Seq("fooId"), Seq("+id")))
      .output("low", ClassDescription.of(classOf[Bar]))
      .output("high", ClassDescription.of(classOf[Bar]))
      .build()

    implicit val context = newOperatorCompilerContext("flowId")

    val thisType = OperatorCompiler.compile(operator, OperatorType.ExtractType)
    context.addClass(context.broadcastIds)
    val cls = context.loadClass[Fragment[Bar]](thisType.getClassName)

    val broadcastIdsCls = context.loadClass(context.broadcastIds.thisType.getClassName)
    def getBroadcastId(marker: MarkerOperator): BroadcastId = {
      val sn = marker.getSerialNumber
      broadcastIdsCls.getField(context.broadcastIds.getField(sn)).get(null).asInstanceOf[BroadcastId]
    }

    val low = new GenericOutputFragment[Bar]()
    val high = new GenericOutputFragment[Bar]()

    val ctor = cls.getConstructor(
      classOf[Map[BroadcastId, Broadcast[_]]],
      classOf[Fragment[_]], classOf[Fragment[_]])

    {
      val foo = new Foo()
      foo.id.modify(10)
      val foos = Seq(foo)
      val shuffleKey = new ShuffleKey(
        WritableSerDe.serialize(foo.id), Array.emptyByteArray)
      val fragment = ctor.newInstance(
        Map(getBroadcastId(foosMarker) -> new MockBroadcast(0, Map(shuffleKey -> foos))),
        low,
        high)
      fragment.reset()
      val bar = new Bar()
      bar.id.modify(10)
      bar.fooId.modify(10)
      fragment.add(bar)
      val lows = low.iterator.toSeq
      assert(lows.size === 0)
      val highs = high.iterator.toSeq
      assert(highs.size === 1)
      assert(highs.head.id.get === 10)

      fragment.reset()
      assert(low.iterator.size === 0)
      assert(high.iterator.size === 0)
    }

    {
      val fragment = ctor.newInstance(
        Map(getBroadcastId(foosMarker) -> new MockBroadcast(0, Map.empty)),
        low,
        high)
      fragment.reset()
      val bar = new Bar()
      bar.id.modify(10)
      bar.fooId.modify(1)
      fragment.add(bar)
      val lows = low.iterator.toSeq
      assert(lows.size === 1)
      assert(lows.head.id.get === 10)
      val highs = high.iterator.toSeq
      assert(highs.size === 0)

      fragment.reset()
      assert(low.iterator.size === 0)
      assert(high.iterator.size === 0)
    }
  }

  it should "compile MasterBranch operator with master selection" in {
    val foosMarker = MarkerOperator.builder(ClassDescription.of(classOf[Foo]))
      .attribute(classOf[PlanMarker], PlanMarker.BROADCAST).build()

    val operator = OperatorExtractor
      .extract(classOf[MasterBranchOp], classOf[MasterBranchOperator], "branchWithSelection")
      .input("foos", ClassDescription.of(classOf[Foo]),
        Groups.parse(Seq("id")), foosMarker.getOutput)
      .input("bars", ClassDescription.of(classOf[Bar]),
        Groups.parse(Seq("fooId"), Seq("+id")))
      .output("low", ClassDescription.of(classOf[Bar]))
      .output("high", ClassDescription.of(classOf[Bar]))
      .build()

    implicit val context = newOperatorCompilerContext("flowId")

    val thisType = OperatorCompiler.compile(operator, OperatorType.ExtractType)
    context.addClass(context.broadcastIds)
    val cls = context.loadClass[Fragment[Bar]](thisType.getClassName)

    val broadcastIdsCls = context.loadClass(context.broadcastIds.thisType.getClassName)
    def getBroadcastId(marker: MarkerOperator): BroadcastId = {
      val sn = marker.getSerialNumber
      broadcastIdsCls.getField(context.broadcastIds.getField(sn)).get(null).asInstanceOf[BroadcastId]
    }

    val low = new GenericOutputFragment[Bar]()
    val high = new GenericOutputFragment[Bar]()

    val ctor = cls.getConstructor(
      classOf[Map[BroadcastId, Broadcast[_]]],
      classOf[Fragment[_]], classOf[Fragment[_]])

    {
      val foo = new Foo()
      foo.id.modify(10)
      val foos = Seq(foo)
      val shuffleKey = new ShuffleKey(
        WritableSerDe.serialize(foo.id), Array.emptyByteArray)
      val fragment = ctor.newInstance(
        Map(getBroadcastId(foosMarker) -> new MockBroadcast(0, Map(shuffleKey -> foos))),
        low,
        high)
      fragment.reset()
      (0 until 10).foreach { i =>
        val bar = new Bar()
        bar.id.modify(i)
        bar.fooId.modify(10)
        fragment.add(bar)
      }
      val lows = low.iterator.toSeq
      assert(lows.size === 5)
      assert(lows.map(_.id.get) === (1 until 10 by 2))
      val highs = high.iterator.toSeq
      assert(highs.size === 5)
      assert(highs.map(_.id.get) === (0 until 10 by 2))

      fragment.reset()
      assert(low.iterator.size === 0)
      assert(high.iterator.size === 0)
    }

    {
      val fragment = ctor.newInstance(
        Map(getBroadcastId(foosMarker) -> new MockBroadcast(0, Map.empty)),
        low,
        high)
      val bar = new Bar()
      bar.id.modify(10)
      bar.fooId.modify(1)
      fragment.add(bar)
      val lows = low.iterator.toSeq
      assert(lows.size === 1)
      assert(lows.head.id.get === 10)
      val highs = high.iterator.toSeq
      assert(highs.size === 0)

      fragment.reset()
      assert(low.iterator.size === 0)
      assert(high.iterator.size === 0)
    }
  }

  it should "compile MasterBranch operator without master selection with projective model" in {
    val foosMarker = MarkerOperator.builder(ClassDescription.of(classOf[Foo]))
      .attribute(classOf[PlanMarker], PlanMarker.BROADCAST).build()

    val operator = OperatorExtractor
      .extract(classOf[MasterBranchOp], classOf[MasterBranchOperator], "branchp")
      .input("foos", ClassDescription.of(classOf[Foo]),
        Groups.parse(Seq("id")), foosMarker.getOutput)
      .input("bars", ClassDescription.of(classOf[Bar]),
        Groups.parse(Seq("fooId"), Seq("+id")))
      .output("low", ClassDescription.of(classOf[Bar]))
      .output("high", ClassDescription.of(classOf[Bar]))
      .build()

    implicit val context = newOperatorCompilerContext("flowId")

    val thisType = OperatorCompiler.compile(operator, OperatorType.ExtractType)
    context.addClass(context.broadcastIds)
    val cls = context.loadClass[Fragment[Bar]](thisType.getClassName)

    val broadcastIdsCls = context.loadClass(context.broadcastIds.thisType.getClassName)
    def getBroadcastId(marker: MarkerOperator): BroadcastId = {
      val sn = marker.getSerialNumber
      broadcastIdsCls.getField(context.broadcastIds.getField(sn)).get(null).asInstanceOf[BroadcastId]
    }

    val low = new GenericOutputFragment[Bar]()
    val high = new GenericOutputFragment[Bar]()

    val ctor = cls.getConstructor(
      classOf[Map[BroadcastId, Broadcast[_]]],
      classOf[Fragment[_]], classOf[Fragment[_]])

    {
      val foo = new Foo()
      foo.id.modify(10)
      val foos = Seq(foo)
      val shuffleKey = new ShuffleKey(
        WritableSerDe.serialize(foo.id), Array.emptyByteArray)
      val fragment = ctor.newInstance(
        Map(getBroadcastId(foosMarker) -> new MockBroadcast(0, Map(shuffleKey -> foos))),
        low,
        high)
      fragment.reset()
      val bar = new Bar()
      bar.id.modify(10)
      bar.fooId.modify(10)
      fragment.add(bar)
      val lows = low.iterator.toSeq
      assert(lows.size === 0)
      val highs = high.iterator.toSeq
      assert(highs.size === 1)
      assert(highs.head.id.get === 10)

      fragment.reset()
      assert(low.iterator.size === 0)
      assert(high.iterator.size === 0)
    }

    {
      val fragment = ctor.newInstance(
        Map(getBroadcastId(foosMarker) -> new MockBroadcast(0, Map.empty)),
        low,
        high)
      fragment.reset()
      val foos = Seq.empty[Foo]
      val bar = new Bar()
      bar.id.modify(10)
      bar.fooId.modify(1)
      fragment.add(bar)
      val lows = low.iterator.toSeq
      assert(lows.size === 1)
      assert(lows.head.id.get === 10)
      val highs = high.iterator.toSeq
      assert(highs.size === 0)

      fragment.reset()
      assert(low.iterator.size === 0)
      assert(high.iterator.size === 0)
    }
  }

  it should "compile MasterBranch operator with master selection with projective model" in {
    val foosMarker = MarkerOperator.builder(ClassDescription.of(classOf[Foo]))
      .attribute(classOf[PlanMarker], PlanMarker.BROADCAST).build()

    val operator = OperatorExtractor
      .extract(classOf[MasterBranchOp], classOf[MasterBranchOperator], "branchWithSelectionp")
      .input("foos", ClassDescription.of(classOf[Foo]),
        Groups.parse(Seq("id")), foosMarker.getOutput)
      .input("bars", ClassDescription.of(classOf[Bar]),
        Groups.parse(Seq("fooId"), Seq("+id")))
      .output("low", ClassDescription.of(classOf[Bar]))
      .output("high", ClassDescription.of(classOf[Bar]))
      .build()

    implicit val context = newOperatorCompilerContext("flowId")

    val thisType = OperatorCompiler.compile(operator, OperatorType.ExtractType)
    context.addClass(context.broadcastIds)
    val cls = context.loadClass[Fragment[Bar]](thisType.getClassName)

    val broadcastIdsCls = context.loadClass(context.broadcastIds.thisType.getClassName)
    def getBroadcastId(marker: MarkerOperator): BroadcastId = {
      val sn = marker.getSerialNumber
      broadcastIdsCls.getField(context.broadcastIds.getField(sn)).get(null).asInstanceOf[BroadcastId]
    }

    val low = new GenericOutputFragment[Bar]()
    val high = new GenericOutputFragment[Bar]()

    val ctor = cls.getConstructor(
      classOf[Map[BroadcastId, Broadcast[_]]],
      classOf[Fragment[_]], classOf[Fragment[_]])

    {
      val foo = new Foo()
      foo.id.modify(10)
      val foos = Seq(foo)
      val shuffleKey = new ShuffleKey(
        WritableSerDe.serialize(foo.id), Array.emptyByteArray)
      val fragment = ctor.newInstance(
        Map(getBroadcastId(foosMarker) -> new MockBroadcast(0, Map(shuffleKey -> foos))),
        low,
        high)
      fragment.reset()
      val bars = (0 until 10).map { i =>
        val bar = new Bar()
        bar.id.modify(i)
        bar.fooId.modify(10)
        fragment.add(bar)
      }
      val lows = low.iterator.toSeq
      assert(lows.size === 5)
      assert(lows.map(_.id.get) === (1 until 10 by 2))
      val highs = high.iterator.toSeq
      assert(highs.size === 5)
      assert(highs.map(_.id.get) === (0 until 10 by 2))

      fragment.reset()
      assert(low.iterator.size === 0)
      assert(high.iterator.size === 0)
    }

    {
      val fragment = ctor.newInstance(
        Map(getBroadcastId(foosMarker) -> new MockBroadcast(0, Map.empty)),
        low,
        high)
      fragment.reset()
      val bar = new Bar()
      bar.id.modify(10)
      bar.fooId.modify(1)
      val bars = Seq(bar)
      fragment.add(bar)
      val lows = low.iterator.toSeq
      assert(lows.size === 1)
      assert(lows.head.id.get === 10)
      val highs = high.iterator.toSeq
      assert(highs.size === 0)

      fragment.reset()
      assert(low.iterator.size === 0)
      assert(high.iterator.size === 0)
    }
  }

  it should "compile MasterBranch operator with master from core.empty" in {
    val operator = OperatorExtractor
      .extract(classOf[MasterBranchOp], classOf[MasterBranchOperator], "branch")
      .input("foos", ClassDescription.of(classOf[Foo]),
        Groups.parse(Seq("id")))
      .input("bars", ClassDescription.of(classOf[Bar]),
        Groups.parse(Seq("fooId"), Seq("+id")))
      .output("low", ClassDescription.of(classOf[Bar]))
      .output("high", ClassDescription.of(classOf[Bar]))
      .build()

    implicit val context = newOperatorCompilerContext("flowId")

    val thisType = OperatorCompiler.compile(operator, OperatorType.ExtractType)
    context.addClass(context.broadcastIds)
    val cls = context.loadClass[Fragment[Bar]](thisType.getClassName)

    val low = new GenericOutputFragment[Bar]()
    val high = new GenericOutputFragment[Bar]()

    val ctor = cls.getConstructor(
      classOf[Map[BroadcastId, Broadcast[_]]],
      classOf[Fragment[_]], classOf[Fragment[_]])

    {
      val fragment = ctor.newInstance(Map.empty, low, high)
      fragment.reset()
      val bar = new Bar()
      bar.id.modify(10)
      bar.fooId.modify(1)
      fragment.add(bar)
      val lows = low.iterator.toSeq
      assert(lows.size === 1)
      assert(lows.head.id.get === 10)
      val highs = high.iterator.toSeq
      assert(highs.size === 0)

      fragment.reset()
      assert(low.iterator.size === 0)
      assert(high.iterator.size === 0)
    }
  }
}

object BroadcastMasterBranchOperatorCompilerSpec {

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

  class MasterBranchOperator {

    @MasterBranchOp
    def branch(foo: Foo, bar: Bar): BranchOperatorCompilerSpecTestBranch = {
      if (foo == null || foo.id.get < 5) {
        BranchOperatorCompilerSpecTestBranch.LOW
      } else {
        BranchOperatorCompilerSpecTestBranch.HIGH
      }
    }

    @MasterBranchOp(selection = "select")
    def branchWithSelection(foo: Foo, bar: Bar): BranchOperatorCompilerSpecTestBranch = {
      if (foo == null || foo.id.get < 5) {
        BranchOperatorCompilerSpecTestBranch.LOW
      } else {
        BranchOperatorCompilerSpecTestBranch.HIGH
      }
    }

    @MasterSelection
    def select(foos: JList[Foo], bar: Bar): Foo = {
      if (bar.id.get % 2 == 0) {
        foos.headOption.orNull
      } else {
        null
      }
    }

    @MasterBranchOp
    def branchp[F <: FooP, B <: BarP](foo: F, bar: B): BranchOperatorCompilerSpecTestBranch = {
      if (foo == null || foo.getIdOption.get < 5) {
        BranchOperatorCompilerSpecTestBranch.LOW
      } else {
        BranchOperatorCompilerSpecTestBranch.HIGH
      }
    }

    @MasterBranchOp(selection = "selectp")
    def branchWithSelectionp[F <: FooP, B <: BarP](foo: F, bar: B): BranchOperatorCompilerSpecTestBranch = {
      if (foo == null || foo.getIdOption.get < 5) {
        BranchOperatorCompilerSpecTestBranch.LOW
      } else {
        BranchOperatorCompilerSpecTestBranch.HIGH
      }
    }

    @MasterSelection
    def selectp[Foo <: FooP, B <: BarP](foos: JList[Foo], bar: B): Foo = {
      if (bar.getIdOption.get % 2 == 0) {
        if (foos.size > 0) {
          foos.head
        } else {
          null.asInstanceOf[Foo]
        }
      } else {
        null.asInstanceOf[Foo]
      }
    }
  }
}