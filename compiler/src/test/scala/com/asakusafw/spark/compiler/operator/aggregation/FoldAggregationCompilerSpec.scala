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
package operator.aggregation

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import scala.collection.JavaConversions._

import com.asakusafw.lang.compiler.model.description.{ ClassDescription, ImmediateDescription }
import com.asakusafw.lang.compiler.model.testing.OperatorExtractor
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.runtime.value.{ IntOption, StringOption }
import com.asakusafw.spark.compiler.spi.AggregationCompiler
import com.asakusafw.spark.runtime.aggregation.Aggregation
import com.asakusafw.spark.tools.asm._
import com.asakusafw.vocabulary.flow.processor.PartialAggregation
import com.asakusafw.vocabulary.operator.Fold

@RunWith(classOf[JUnitRunner])
class FoldAggregationCompilerSpecTest extends FoldAggregationCompilerSpec

class FoldAggregationCompilerSpec extends FlatSpec with UsingCompilerContext {

  import FoldAggregationCompilerSpec._

  behavior of classOf[FoldAggregationCompiler].getSimpleName

  for {
    s <- Seq("s", null)
  } {
    it should s"compile Aggregation for Fold${if (s == null) " with argument null" else ""}" in {
      val operator = OperatorExtractor
        .extract(classOf[Fold], classOf[FoldOperator], "fold")
        .input("input", ClassDescription.of(classOf[Foo]))
        .output("output", ClassDescription.of(classOf[Foo]))
        .argument("n", ImmediateDescription.of(10))
        .argument("s", ImmediateDescription.of(s))
        .build()

      implicit val context = newAggregationCompilerContext("flowId")

      val thisType = AggregationCompiler.compile(operator)
      val cls = context.loadClass[Aggregation[Seq[_], Foo, Foo]](thisType.getClassName)

      val aggregation = cls.newInstance()
      assert(aggregation.mapSideCombine === true)

      val valueCombiner = aggregation.valueCombiner()
      valueCombiner.insertAll((0 until 100).map { i =>
        val foo = new Foo()
        foo.i.modify(i)
        (Seq(i % 2), foo)
      }.iterator)
      assert(valueCombiner.toSeq.map { case (k, v) => k -> (v.i.get, v.s.or("")) }
        === Seq(
          (Seq(0), ((0 until 100 by 2).sum + 10 * 49, if (s == null) "" else "s" * 49)),
          (Seq(1), ((1 until 100 by 2).sum + 10 * 49, if (s == null) "" else "s" * 49))))

      val combinerCombiner = aggregation.combinerCombiner()
      combinerCombiner.insertAll((0 until 100).map { i =>
        val foo = new Foo()
        foo.i.modify(i)
        (Seq(i % 2), foo)
      }.iterator)
      assert(combinerCombiner.toSeq.map { case (k, v) => k -> (v.i.get, v.s.or("")) }
        === Seq(
          (Seq(0), ((0 until 100 by 2).sum + 10 * 49, if (s == null) "" else "s" * 49)),
          (Seq(1), ((1 until 100 by 2).sum + 10 * 49, if (s == null) "" else "s" * 49))))
    }
  }

  it should "compile Aggregation for Fold with projective model" in {
    val operator = OperatorExtractor
      .extract(classOf[Fold], classOf[FoldOperator], "foldp")
      .input("input", ClassDescription.of(classOf[Foo]))
      .output("output", ClassDescription.of(classOf[Foo]))
      .argument("n", ImmediateDescription.of(10))
      .build()

    implicit val context = newAggregationCompilerContext("flowId")

    val thisType = AggregationCompiler.compile(operator)
    val cls = context.loadClass[Aggregation[Seq[_], Foo, Foo]](thisType.getClassName)

    val aggregation = cls.newInstance()
    assert(aggregation.mapSideCombine === true)

    val valueCombiner = aggregation.valueCombiner()
    valueCombiner.insertAll((0 until 100).map { i =>
      val foo = new Foo()
      foo.i.modify(i)
      (Seq(i % 2), foo)
    }.iterator)
    assert(valueCombiner.toSeq.map { case (k, v) => k -> (v.i.get, v.s.or("")) }
      === Seq(
        (Seq(0), ((0 until 100 by 2).sum + 10 * 49, "")),
        (Seq(1), ((1 until 100 by 2).sum + 10 * 49, ""))))

    val combinerCombiner = aggregation.combinerCombiner()
    combinerCombiner.insertAll((0 until 100).map { i =>
      val foo = new Foo()
      foo.i.modify(i)
      (Seq(i % 2), foo)
    }.iterator)
    assert(combinerCombiner.toSeq.map { case (k, v) => k -> (v.i.get, v.s.or("")) }
      === Seq(
        (Seq(0), ((0 until 100 by 2).sum + 10 * 49, "")),
        (Seq(1), ((1 until 100 by 2).sum + 10 * 49, ""))))
  }
}

object FoldAggregationCompilerSpec {

  trait FooP {
    def getIOption: IntOption
  }

  class Foo extends DataModel[Foo] with FooP {

    val i = new IntOption()
    val s = new StringOption()

    override def reset(): Unit = {
      i.setNull()
      s.setNull()
    }

    override def copyFrom(other: Foo): Unit = {
      i.copyFrom(other.i)
      s.copyFrom(other.s)
    }

    def getIOption: IntOption = i
  }

  class FoldOperator {

    @Fold(partialAggregation = PartialAggregation.PARTIAL)
    def fold(acc: Foo, value: Foo, n: Int, s: String): Unit = {
      acc.i.add(value.i)
      acc.i.add(n)
      if (s != null) {
        acc.s.modify(acc.s.or("") + s)
      }
    }

    @Fold(partialAggregation = PartialAggregation.PARTIAL)
    def foldp[F <: FooP](acc: F, value: F, n: Int): Unit = {
      acc.getIOption.add(value.getIOption)
      acc.getIOption.add(n)
    }
  }
}
