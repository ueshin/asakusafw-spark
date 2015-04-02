package com.asakusafw.spark.compiler

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import java.io.{ File, DataInput, DataOutput }
import java.net.URLClassLoader
import java.util.{ List => JList }

import scala.collection.JavaConversions._

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{ NullWritable, Writable }
import org.apache.spark.{ SparkConf, SparkContext }
import org.apache.spark.SparkContext._

import com.asakusafw.bridge.stage.StageInfo
import com.asakusafw.lang.compiler.api.CompilerOptions
import com.asakusafw.lang.compiler.api.testing.MockJobflowProcessorContext
import com.asakusafw.lang.compiler.model.PropertyName
import com.asakusafw.lang.compiler.model.description.ClassDescription
import com.asakusafw.lang.compiler.model.graph._
import com.asakusafw.lang.compiler.model.info.ExternalInputInfo
import com.asakusafw.lang.compiler.model.testing.OperatorExtractor
import com.asakusafw.lang.compiler.planning._
import com.asakusafw.runtime.compatibility.JobCompatibility
import com.asakusafw.runtime.core.Result
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.runtime.stage.StageConstants
import com.asakusafw.runtime.stage.input.TemporaryInputFormat
import com.asakusafw.runtime.stage.output.TemporaryOutputFormat
import com.asakusafw.runtime.value._
import com.asakusafw.spark.runtime._
import com.asakusafw.spark.tools.asm._
import com.asakusafw.vocabulary.flow.processor.PartialAggregation
import com.asakusafw.vocabulary.model.{ Key, Summarized }
import com.asakusafw.vocabulary.operator._

@RunWith(classOf[JUnitRunner])
class SparkClientCompilerSpecTest extends SparkClientCompilerSpec

class SparkClientCompilerSpec extends FlatSpec with LoadClassSugar {

  import SparkClientCompilerSpec._

  behavior of classOf[SparkClientCompiler].getSimpleName

  it should "compile Spark client from simple plan" in {
    val tmpDir = File.createTempFile("test-", null)
    tmpDir.delete
    val classpath = new File(tmpDir, "classes").getAbsoluteFile
    classpath.mkdirs()
    val path = new File(tmpDir, "tmp").getAbsolutePath

    spark { sc =>
      val hoges = sc.parallelize(0 until 100).map { i =>
        val hoge = new Hoge()
        hoge.id.modify(i)
        hoge
      }
      val job = JobCompatibility.newJob(sc.hadoopConfiguration)
      job.setOutputKeyClass(classOf[NullWritable])
      job.setOutputValueClass(classOf[Hoge])
      job.setOutputFormatClass(classOf[TemporaryOutputFormat[Hoge]])
      TemporaryOutputFormat.setOutputPath(job, new Path(path, "extenal/input/hoge"))
      hoges.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
    }

    val inputOperator = ExternalInput
      .newInstance("hoge/part-*",
        new ExternalInputInfo.Basic(
          ClassDescription.of(classOf[Hoge]),
          "test",
          ClassDescription.of(classOf[Hoge]),
          ExternalInputInfo.DataSize.UNKNOWN))

    val outputOperator = ExternalOutput
      .newInstance("output", inputOperator.getOperatorPort)

    val graph = new OperatorGraph(Seq(inputOperator, outputOperator))

    val compiler = new SparkClientCompiler {

      override def preparePlan(graph: OperatorGraph, flowId: String, dump: Boolean): Plan = {
        val plan = super.preparePlan(graph, flowId, true)
        assert(plan.getElements.size === 2)
        plan
      }
    }

    val jpContext = new MockJobflowProcessorContext(
      new CompilerOptions("buildid", path, Map.empty[String, String]),
      Thread.currentThread.getContextClassLoader,
      classpath)
    val jobflow = new Jobflow("flowId", ClassDescription.of(classOf[SparkClientCompilerSpec]), graph)

    compiler.process(jpContext, jobflow)

    val cl = Thread.currentThread.getContextClassLoader
    try {
      val classloader = new URLClassLoader(Array(classpath.toURI.toURL), cl)
      Thread.currentThread.setContextClassLoader(classloader)
      val cls = Class.forName("com.asakusafw.generated.spark.flowId.SparkClient", true, classloader)
        .asSubclass(classOf[SparkClient])
      val instance = cls.newInstance

      val conf = new SparkConf()
      conf.setAppName("AsakusaSparkClient")
      conf.setMaster("local[*]")

      val stageInfo = new StageInfo(
        sys.props("user.name"), "batchId", "flowId", null, "executionId", Map.empty[String, String])
      conf.setHadoopConf(Props.StageInfo, stageInfo.serialize)

      instance.execute(conf)
    } finally {
      Thread.currentThread.setContextClassLoader(cl)
    }

    spark { sc =>
      val job = JobCompatibility.newJob(sc.hadoopConfiguration)
      TemporaryInputFormat.setInputPaths(job, Seq(new Path(path, s"output/*/part-*")))
      val rdd = sc.newAPIHadoopRDD(
        job.getConfiguration,
        classOf[TemporaryInputFormat[Hoge]],
        classOf[NullWritable],
        classOf[Hoge])
      assert(rdd.map(_._2.id.get).collect === (0 until 100))
    }
  }

  it should "compile Spark client with Extract" in {
    val tmpDir = File.createTempFile("test-", null)
    tmpDir.delete
    val classpath = new File(tmpDir, "classes").getAbsoluteFile
    classpath.mkdirs()
    val path = new File(tmpDir, "tmp").getAbsolutePath

    spark { sc =>
      val hoges = sc.parallelize(0 until 100).map { i =>
        val hoge = new Hoge()
        hoge.id.modify(i)
        hoge
      }
      val job = JobCompatibility.newJob(sc.hadoopConfiguration)
      job.setOutputKeyClass(classOf[NullWritable])
      job.setOutputValueClass(classOf[Hoge])
      job.setOutputFormatClass(classOf[TemporaryOutputFormat[Hoge]])
      TemporaryOutputFormat.setOutputPath(job, new Path(path, "extenal/input/hoge"))
      hoges.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
    }

    val inputOperator = ExternalInput
      .newInstance("hoge/part-*",
        new ExternalInputInfo.Basic(
          ClassDescription.of(classOf[Hoge]),
          "test",
          ClassDescription.of(classOf[Hoge]),
          ExternalInputInfo.DataSize.UNKNOWN))

    val extractOperator = OperatorExtractor
      .extract(classOf[Extract], classOf[Ops], "extract")
      .input("hoge", ClassDescription.of(classOf[Hoge]), inputOperator.getOperatorPort)
      .output("evenResult", ClassDescription.of(classOf[Hoge]))
      .output("oddResult", ClassDescription.of(classOf[Hoge]))
      .build()

    val evenOutputOperator = ExternalOutput
      .newInstance("even", extractOperator.findOutput("evenResult"))

    val oddOutputOperator = ExternalOutput
      .newInstance("odd", extractOperator.findOutput("oddResult"))

    val graph = new OperatorGraph(Seq(
      inputOperator,
      extractOperator,
      evenOutputOperator,
      oddOutputOperator))

    val compiler = new SparkClientCompiler {

      override def preparePlan(graph: OperatorGraph, flowId: String, dump: Boolean): Plan = {
        val plan = super.preparePlan(graph, flowId, true)
        assert(plan.getElements.size === 3)
        plan
      }
    }

    val jpContext = new MockJobflowProcessorContext(
      new CompilerOptions("buildid", path, Map.empty[String, String]),
      Thread.currentThread.getContextClassLoader,
      classpath)
    val jobflow = new Jobflow("flowId", ClassDescription.of(classOf[SparkClientCompilerSpec]), graph)

    compiler.process(jpContext, jobflow)

    val cl = Thread.currentThread.getContextClassLoader
    try {
      val classloader = new URLClassLoader(Array(classpath.toURI.toURL), cl)
      Thread.currentThread.setContextClassLoader(classloader)
      val cls = Class.forName("com.asakusafw.generated.spark.flowId.SparkClient", true, classloader)
        .asSubclass(classOf[SparkClient])
      val instance = cls.newInstance

      val conf = new SparkConf()
      conf.setAppName("AsakusaSparkClient")
      conf.setMaster("local[*]")

      val stageInfo = new StageInfo(
        sys.props("user.name"), "batchId", "flowId", null, "executionId", Map.empty[String, String])
      conf.setHadoopConf(Props.StageInfo, stageInfo.serialize)

      instance.execute(conf)
    } finally {
      Thread.currentThread.setContextClassLoader(cl)
    }

    spark { sc =>
      {
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        TemporaryInputFormat.setInputPaths(job, Seq(new Path(path, s"even/*/part-*")))
        val rdd = sc.newAPIHadoopRDD(
          job.getConfiguration,
          classOf[TemporaryInputFormat[Hoge]],
          classOf[NullWritable],
          classOf[Hoge])
        assert(rdd.map(_._2.id.get).collect === (0 until 100).filter(_ % 2 == 0))
      }
      {
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        TemporaryInputFormat.setInputPaths(job, Seq(new Path(path, s"odd/*/part-*")))
        val rdd = sc.newAPIHadoopRDD(
          job.getConfiguration,
          classOf[TemporaryInputFormat[Hoge]],
          classOf[NullWritable],
          classOf[Hoge])
        assert(rdd.map(_._2.id.get).collect === (0 until 100).filterNot(_ % 2 == 0))
      }
    }
  }

  it should "compile Spark client with CoGroup" in {
    val tmpDir = File.createTempFile("test-", null)
    tmpDir.delete
    val classpath = new File(tmpDir, "classes").getAbsoluteFile
    classpath.mkdirs()
    val path = new File(tmpDir, "tmp").getAbsolutePath

    spark { sc =>
      {
        val hoges = sc.parallelize(0 until 5).map { i =>
          val hoge = new Hoge()
          hoge.id.modify(i)
          hoge
        }
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        job.setOutputKeyClass(classOf[NullWritable])
        job.setOutputValueClass(classOf[Hoge])
        job.setOutputFormatClass(classOf[TemporaryOutputFormat[Hoge]])
        TemporaryOutputFormat.setOutputPath(job, new Path(path, "extenal/input/hoge1"))
        hoges.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
      }
      {
        val hoges = sc.parallelize(5 until 10).map { i =>
          val hoge = new Hoge()
          hoge.id.modify(i)
          hoge
        }
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        job.setOutputKeyClass(classOf[NullWritable])
        job.setOutputValueClass(classOf[Hoge])
        job.setOutputFormatClass(classOf[TemporaryOutputFormat[Hoge]])
        TemporaryOutputFormat.setOutputPath(job, new Path(path, "extenal/input/hoge2"))
        hoges.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
      }
      {
        val foos = sc.parallelize(0 until 10).flatMap(i => (0 until i).map { j =>
          val foo = new Foo()
          foo.id.modify(10 + j)
          foo.hogeId.modify(i)
          foo
        })
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        job.setOutputKeyClass(classOf[NullWritable])
        job.setOutputValueClass(classOf[Foo])
        job.setOutputFormatClass(classOf[TemporaryOutputFormat[Foo]])
        TemporaryOutputFormat.setOutputPath(job, new Path(path, "extenal/input/foo"))
        foos.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
      }
    }

    val hoge1InputOperator = ExternalInput
      .newInstance("hoge1/part-*",
        new ExternalInputInfo.Basic(
          ClassDescription.of(classOf[Hoge]),
          "hoges1",
          ClassDescription.of(classOf[Hoge]),
          ExternalInputInfo.DataSize.UNKNOWN))

    val hoge2InputOperator = ExternalInput
      .newInstance("hoge2/part-*",
        new ExternalInputInfo.Basic(
          ClassDescription.of(classOf[Hoge]),
          "hoges2",
          ClassDescription.of(classOf[Hoge]),
          ExternalInputInfo.DataSize.UNKNOWN))

    val fooInputOperator = ExternalInput
      .newInstance("foo/part-*",
        new ExternalInputInfo.Basic(
          ClassDescription.of(classOf[Foo]),
          "foos",
          ClassDescription.of(classOf[Foo]),
          ExternalInputInfo.DataSize.UNKNOWN))

    val cogroupOperator = OperatorExtractor
      .extract(classOf[CoGroup], classOf[Ops], "cogroup")
      .input("hoges", ClassDescription.of(classOf[Hoge]),
        new Group(
          Seq(PropertyName.of("id")),
          Seq.empty[Group.Ordering]),
        hoge1InputOperator.getOperatorPort, hoge2InputOperator.getOperatorPort)
      .input("foos", ClassDescription.of(classOf[Foo]),
        new Group(
          Seq(PropertyName.of("hogeId")),
          Seq(new Group.Ordering(PropertyName.of("id"), Group.Direction.ASCENDANT))),
        fooInputOperator.getOperatorPort)
      .output("hogeResult", ClassDescription.of(classOf[Hoge]))
      .output("fooResult", ClassDescription.of(classOf[Foo]))
      .output("hogeError", ClassDescription.of(classOf[Hoge]))
      .output("fooError", ClassDescription.of(classOf[Foo]))
      .build()

    val hogeResultOutputOperator = ExternalOutput
      .newInstance("hogeResult", cogroupOperator.findOutput("hogeResult"))

    val fooResultOutputOperator = ExternalOutput
      .newInstance("fooResult", cogroupOperator.findOutput("fooResult"))

    val hogeErrorOutputOperator = ExternalOutput
      .newInstance("hogeError", cogroupOperator.findOutput("hogeError"))

    val fooErrorOutputOperator = ExternalOutput
      .newInstance("fooError", cogroupOperator.findOutput("fooError"))

    val graph = new OperatorGraph(Seq(
      hoge1InputOperator, hoge2InputOperator, fooInputOperator,
      cogroupOperator,
      hogeResultOutputOperator, fooResultOutputOperator, hogeErrorOutputOperator, fooErrorOutputOperator))

    val compiler = new SparkClientCompiler {

      override def preparePlan(graph: OperatorGraph, flowId: String, dump: Boolean): Plan = {
        val plan = super.preparePlan(graph, flowId, true)
        assert(plan.getElements.size === 8)
        plan
      }
    }

    val jpContext = new MockJobflowProcessorContext(
      new CompilerOptions("buildid", path, Map.empty[String, String]),
      Thread.currentThread.getContextClassLoader,
      classpath)
    val jobflow = new Jobflow("flowId", ClassDescription.of(classOf[SparkClientCompilerSpec]), graph)

    compiler.process(jpContext, jobflow)

    val cl = Thread.currentThread.getContextClassLoader
    try {
      val classloader = new URLClassLoader(Array(classpath.toURI.toURL), cl)
      Thread.currentThread.setContextClassLoader(classloader)
      val cls = Class.forName("com.asakusafw.generated.spark.flowId.SparkClient", true, classloader)
        .asSubclass(classOf[SparkClient])
      val instance = cls.newInstance

      val conf = new SparkConf()
      conf.setAppName("AsakusaSparkClient")
      conf.setMaster("local[*]")

      val stageInfo = new StageInfo(
        sys.props("user.name"), "batchId", "flowId", null, "executionId", Map.empty[String, String])
      conf.setHadoopConf(Props.StageInfo, stageInfo.serialize)

      instance.execute(conf)
    } finally {
      Thread.currentThread.setContextClassLoader(cl)
    }

    spark { sc =>
      {
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        TemporaryInputFormat.setInputPaths(job, Seq(new Path(path, s"hogeResult/*/part-*")))
        val hogeResult = sc.newAPIHadoopRDD(
          job.getConfiguration,
          classOf[TemporaryInputFormat[Hoge]],
          classOf[NullWritable],
          classOf[Hoge]).map(_._2.id.get).collect.toSeq
        assert(hogeResult.size === 1)
        assert(hogeResult(0) === 1)
      }
      {
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        TemporaryInputFormat.setInputPaths(job, Seq(new Path(path, s"fooResult/*/part-*")))
        val fooResult = sc.newAPIHadoopRDD(
          job.getConfiguration,
          classOf[TemporaryInputFormat[Foo]],
          classOf[NullWritable],
          classOf[Foo]).map(_._2).map(foo => (foo.id.get, foo.hogeId.get)).collect.toSeq
        assert(fooResult.size === 1)
        assert(fooResult(0) === (10, 1))
      }
      {
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        TemporaryInputFormat.setInputPaths(job, Seq(new Path(path, s"hogeError/*/part-*")))
        val hogeError = sc.newAPIHadoopRDD(
          job.getConfiguration,
          classOf[TemporaryInputFormat[Hoge]],
          classOf[NullWritable],
          classOf[Hoge]).map(_._2.id.get).collect.toSeq.sorted
        assert(hogeError.size === 9)
        assert(hogeError(0) === 0)
        for (i <- 2 until 10) {
          assert(hogeError(i - 1) === i)
        }
      }
      {
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        TemporaryInputFormat.setInputPaths(job, Seq(new Path(path, s"fooError/*/part-*")))
        val fooError = sc.newAPIHadoopRDD(
          job.getConfiguration,
          classOf[TemporaryInputFormat[Foo]],
          classOf[NullWritable],
          classOf[Foo]).map(_._2).map(foo => (foo.id.get, foo.hogeId.get)).collect.toSeq
          .sortBy(foo => (foo._2, foo._1))
        assert(fooError.size === 44)
        for {
          i <- 2 until 10
          j <- 0 until i
        } {
          assert(fooError((i * (i - 1)) / 2 + j - 1) == (10 + j, i))
        }
      }
    }
  }

  it should "compile Spark client with MasterCheck" in {
    val tmpDir = File.createTempFile("test-", null)
    tmpDir.delete
    val classpath = new File(tmpDir, "classes").getAbsoluteFile
    classpath.mkdirs()
    val path = new File(tmpDir, "tmp").getAbsolutePath

    spark { sc =>
      {
        val hoges = sc.parallelize(0 until 5).map { i =>
          val hoge = new Hoge()
          hoge.id.modify(i)
          hoge
        }
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        job.setOutputKeyClass(classOf[NullWritable])
        job.setOutputValueClass(classOf[Hoge])
        job.setOutputFormatClass(classOf[TemporaryOutputFormat[Hoge]])
        TemporaryOutputFormat.setOutputPath(job, new Path(path, "extenal/input/hoge1"))
        hoges.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
      }
      {
        val hoges = sc.parallelize(5 until 10).map { i =>
          val hoge = new Hoge()
          hoge.id.modify(i)
          hoge
        }
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        job.setOutputKeyClass(classOf[NullWritable])
        job.setOutputValueClass(classOf[Hoge])
        job.setOutputFormatClass(classOf[TemporaryOutputFormat[Hoge]])
        TemporaryOutputFormat.setOutputPath(job, new Path(path, "extenal/input/hoge2"))
        hoges.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
      }
      {
        val foos = sc.parallelize(5 until 15).map { i =>
          val foo = new Foo()
          foo.id.modify(10 + i)
          foo.hogeId.modify(i)
          foo
        }
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        job.setOutputKeyClass(classOf[NullWritable])
        job.setOutputValueClass(classOf[Foo])
        job.setOutputFormatClass(classOf[TemporaryOutputFormat[Foo]])
        TemporaryOutputFormat.setOutputPath(job, new Path(path, "extenal/input/foo"))
        foos.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
      }
    }

    val hoge1InputOperator = ExternalInput
      .newInstance("hoge1/part-*",
        new ExternalInputInfo.Basic(
          ClassDescription.of(classOf[Hoge]),
          "hoges1",
          ClassDescription.of(classOf[Hoge]),
          ExternalInputInfo.DataSize.UNKNOWN))

    val hoge2InputOperator = ExternalInput
      .newInstance("hoge2/part-*",
        new ExternalInputInfo.Basic(
          ClassDescription.of(classOf[Hoge]),
          "hoges2",
          ClassDescription.of(classOf[Hoge]),
          ExternalInputInfo.DataSize.UNKNOWN))

    val fooInputOperator = ExternalInput
      .newInstance("foo/part-*",
        new ExternalInputInfo.Basic(
          ClassDescription.of(classOf[Foo]),
          "foos",
          ClassDescription.of(classOf[Foo]),
          ExternalInputInfo.DataSize.UNKNOWN))

    val masterCheckOperator = OperatorExtractor
      .extract(classOf[MasterCheck], classOf[Ops], "mastercheck")
      .input("hoges", ClassDescription.of(classOf[Hoge]),
        new Group(
          Seq(PropertyName.of("id")),
          Seq.empty[Group.Ordering]),
        hoge1InputOperator.getOperatorPort, hoge2InputOperator.getOperatorPort)
      .input("foos", ClassDescription.of(classOf[Foo]),
        new Group(
          Seq(PropertyName.of("hogeId")),
          Seq(new Group.Ordering(PropertyName.of("id"), Group.Direction.ASCENDANT))),
        fooInputOperator.getOperatorPort)
      .output("found", ClassDescription.of(classOf[Foo]))
      .output("missed", ClassDescription.of(classOf[Foo]))
      .build()

    val foundOutputOperator = ExternalOutput
      .newInstance("found", masterCheckOperator.findOutput("found"))

    val missedOutputOperator = ExternalOutput
      .newInstance("missed", masterCheckOperator.findOutput("missed"))

    val graph = new OperatorGraph(Seq(
      hoge1InputOperator, hoge2InputOperator, fooInputOperator,
      masterCheckOperator,
      foundOutputOperator, missedOutputOperator))

    val compiler = new SparkClientCompiler {

      override def preparePlan(graph: OperatorGraph, flowId: String, dump: Boolean): Plan = {
        val plan = super.preparePlan(graph, flowId, true)
        assert(plan.getElements.size === 6)
        plan
      }
    }

    val jpContext = new MockJobflowProcessorContext(
      new CompilerOptions("buildid", path, Map.empty[String, String]),
      Thread.currentThread.getContextClassLoader,
      classpath)
    val jobflow = new Jobflow("flowId", ClassDescription.of(classOf[SparkClientCompilerSpec]), graph)

    compiler.process(jpContext, jobflow)

    val cl = Thread.currentThread.getContextClassLoader
    try {
      val classloader = new URLClassLoader(Array(classpath.toURI.toURL), cl)
      Thread.currentThread.setContextClassLoader(classloader)
      val cls = Class.forName("com.asakusafw.generated.spark.flowId.SparkClient", true, classloader)
        .asSubclass(classOf[SparkClient])
      val instance = cls.newInstance

      val conf = new SparkConf()
      conf.setAppName("AsakusaSparkClient")
      conf.setMaster("local[*]")

      val stageInfo = new StageInfo(
        sys.props("user.name"), "batchId", "flowId", null, "executionId", Map.empty[String, String])
      conf.setHadoopConf(Props.StageInfo, stageInfo.serialize)

      instance.execute(conf)
    } finally {
      Thread.currentThread.setContextClassLoader(cl)
    }

    spark { sc =>
      {
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        TemporaryInputFormat.setInputPaths(job, Seq(new Path(path, s"found/*/part-*")))
        val found = sc.newAPIHadoopRDD(
          job.getConfiguration,
          classOf[TemporaryInputFormat[Foo]],
          classOf[NullWritable],
          classOf[Foo]).map(_._2.id.get).collect.toSeq.sorted
        assert(found.size === 5)
        assert(found === (5 until 10).map(10 + _))
      }
      {
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        TemporaryInputFormat.setInputPaths(job, Seq(new Path(path, s"missed/*/part-*")))
        val missed = sc.newAPIHadoopRDD(
          job.getConfiguration,
          classOf[TemporaryInputFormat[Foo]],
          classOf[NullWritable],
          classOf[Foo]).map(_._2).map(foo => (foo.id.get, foo.hogeId.get)).collect.toSeq.sorted
        assert(missed.size === 5)
        assert(missed === (10 until 15).map(i => (10 + i, i)))
      }
    }
  }

  it should "compile Spark client with broadcast MasterCheck" in {
    val tmpDir = File.createTempFile("test-", null)
    tmpDir.delete
    val classpath = new File(tmpDir, "classes").getAbsoluteFile
    classpath.mkdirs()
    val path = new File(tmpDir, "tmp").getAbsolutePath

    spark { sc =>
      {
        val hoges = sc.parallelize(0 until 10).map { i =>
          val hoge = new Hoge()
          hoge.id.modify(i)
          hoge
        }
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        job.setOutputKeyClass(classOf[NullWritable])
        job.setOutputValueClass(classOf[Hoge])
        job.setOutputFormatClass(classOf[TemporaryOutputFormat[Hoge]])
        TemporaryOutputFormat.setOutputPath(job, new Path(path, "extenal/input/hoge"))
        hoges.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
      }
      {
        val foos = sc.parallelize(5 until 15).map { i =>
          val foo = new Foo()
          foo.id.modify(10 + i)
          foo.hogeId.modify(i)
          foo
        }
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        job.setOutputKeyClass(classOf[NullWritable])
        job.setOutputValueClass(classOf[Foo])
        job.setOutputFormatClass(classOf[TemporaryOutputFormat[Foo]])
        TemporaryOutputFormat.setOutputPath(job, new Path(path, "extenal/input/foo"))
        foos.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
      }
    }

    val hogeInputOperator = ExternalInput
      .newInstance("hoge/part-*",
        new ExternalInputInfo.Basic(
          ClassDescription.of(classOf[Hoge]),
          "hoges1",
          ClassDescription.of(classOf[Hoge]),
          ExternalInputInfo.DataSize.TINY))

    val fooInputOperator = ExternalInput
      .newInstance("foo/part-*",
        new ExternalInputInfo.Basic(
          ClassDescription.of(classOf[Foo]),
          "foos",
          ClassDescription.of(classOf[Foo]),
          ExternalInputInfo.DataSize.UNKNOWN))

    val masterCheckOperator = OperatorExtractor
      .extract(classOf[MasterCheck], classOf[Ops], "mastercheck")
      .input("hoges", ClassDescription.of(classOf[Hoge]),
        new Group(
          Seq(PropertyName.of("id")),
          Seq.empty[Group.Ordering]),
        hogeInputOperator.getOperatorPort)
      .input("foos", ClassDescription.of(classOf[Foo]),
        new Group(
          Seq(PropertyName.of("hogeId")),
          Seq.empty[Group.Ordering]),
        fooInputOperator.getOperatorPort)
      .output("found", ClassDescription.of(classOf[Foo]))
      .output("missed", ClassDescription.of(classOf[Foo]))
      .build()

    val foundOutputOperator = ExternalOutput
      .newInstance("found", masterCheckOperator.findOutput("found"))

    val missedOutputOperator = ExternalOutput
      .newInstance("missed", masterCheckOperator.findOutput("missed"))

    val graph = new OperatorGraph(Seq(
      hogeInputOperator, fooInputOperator,
      masterCheckOperator,
      foundOutputOperator, missedOutputOperator))

    val compiler = new SparkClientCompiler {

      override def preparePlan(graph: OperatorGraph, flowId: String, dump: Boolean): Plan = {
        val plan = super.preparePlan(graph, flowId, true)
        assert(plan.getElements.size === 4)
        plan
      }
    }

    val jpContext = new MockJobflowProcessorContext(
      new CompilerOptions("buildid", path, Map.empty[String, String]),
      Thread.currentThread.getContextClassLoader,
      classpath)
    val jobflow = new Jobflow("flowId", ClassDescription.of(classOf[SparkClientCompilerSpec]), graph)

    compiler.process(jpContext, jobflow)

    val cl = Thread.currentThread.getContextClassLoader
    try {
      val classloader = new URLClassLoader(Array(classpath.toURI.toURL), cl)
      Thread.currentThread.setContextClassLoader(classloader)
      val cls = Class.forName("com.asakusafw.generated.spark.flowId.SparkClient", true, classloader)
        .asSubclass(classOf[SparkClient])
      val instance = cls.newInstance

      val conf = new SparkConf()
      conf.setAppName("AsakusaSparkClient")
      conf.setMaster("local[*]")

      val stageInfo = new StageInfo(
        sys.props("user.name"), "batchId", "flowId", null, "executionId", Map.empty[String, String])
      conf.setHadoopConf(Props.StageInfo, stageInfo.serialize)

      instance.execute(conf)
    } finally {
      Thread.currentThread.setContextClassLoader(cl)
    }

    spark { sc =>
      {
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        TemporaryInputFormat.setInputPaths(job, Seq(new Path(path, s"found/*/part-*")))
        val found = sc.newAPIHadoopRDD(
          job.getConfiguration,
          classOf[TemporaryInputFormat[Foo]],
          classOf[NullWritable],
          classOf[Foo]).map(_._2.id.get).collect.toSeq
        assert(found.size === 5)
        assert(found === (5 until 10).map(10 + _))
      }
      {
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        TemporaryInputFormat.setInputPaths(job, Seq(new Path(path, s"missed/*/part-*")))
        val missed = sc.newAPIHadoopRDD(
          job.getConfiguration,
          classOf[TemporaryInputFormat[Foo]],
          classOf[NullWritable],
          classOf[Foo]).map(_._2).map(foo => (foo.id.get, foo.hogeId.get)).collect.toSeq
        assert(missed.size === 5)
        assert(missed === (10 until 15).map(i => (10 + i, i)))
      }
    }
  }

  it should "compile Spark client with Fold" in {
    val tmpDir = File.createTempFile("test-", null)
    tmpDir.delete
    val classpath = new File(tmpDir, "classes").getAbsoluteFile
    classpath.mkdirs()
    val path = new File(tmpDir, "tmp").getAbsolutePath

    spark { sc =>
      {
        val baa1 = sc.parallelize(0 until 5).map { i =>
          val baa = new Baa()
          baa.id.modify(i % 2)
          baa.price.modify(100 * i)
          baa
        }
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        job.setOutputKeyClass(classOf[NullWritable])
        job.setOutputValueClass(classOf[Baa])
        job.setOutputFormatClass(classOf[TemporaryOutputFormat[Baa]])
        TemporaryOutputFormat.setOutputPath(job, new Path(path, "extenal/input/baa1"))
        baa1.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
      }
      {
        val baa2 = sc.parallelize(5 until 10).map { i =>
          val baa = new Baa()
          baa.id.modify(i % 2)
          baa.price.modify(100 * i)
          baa
        }
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        job.setOutputKeyClass(classOf[NullWritable])
        job.setOutputValueClass(classOf[Baa])
        job.setOutputFormatClass(classOf[TemporaryOutputFormat[Baa]])
        TemporaryOutputFormat.setOutputPath(job, new Path(path, "extenal/input/baa2"))
        baa2.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
      }
    }

    val baa1InputOperator = ExternalInput
      .newInstance("baa1/part-*",
        new ExternalInputInfo.Basic(
          ClassDescription.of(classOf[Baa]),
          "baa1",
          ClassDescription.of(classOf[Baa]),
          ExternalInputInfo.DataSize.UNKNOWN))

    val baa2InputOperator = ExternalInput
      .newInstance("baa2/part-*",
        new ExternalInputInfo.Basic(
          ClassDescription.of(classOf[Baa]),
          "baa2",
          ClassDescription.of(classOf[Baa]),
          ExternalInputInfo.DataSize.UNKNOWN))

    val foldOperator = OperatorExtractor
      .extract(classOf[Fold], classOf[Ops], "fold")
      .input("baas", ClassDescription.of(classOf[Baa]),
        new Group(
          Seq(PropertyName.of("id")),
          Seq.empty[Group.Ordering]),
        baa1InputOperator.getOperatorPort, baa2InputOperator.getOperatorPort)
      .output("result", ClassDescription.of(classOf[Baa]))
      .build()

    val resultOutputOperator = ExternalOutput
      .newInstance("result", foldOperator.findOutput("result"))

    val graph = new OperatorGraph(Seq(
      baa1InputOperator, baa2InputOperator,
      foldOperator,
      resultOutputOperator))

    val compiler = new SparkClientCompiler {

      override def preparePlan(graph: OperatorGraph, flowId: String, dump: Boolean): Plan = {
        val plan = super.preparePlan(graph, flowId, true)
        assert(plan.getElements.size === 4)
        plan
      }
    }

    val jpContext = new MockJobflowProcessorContext(
      new CompilerOptions("buildid", path, Map.empty[String, String]),
      Thread.currentThread.getContextClassLoader,
      classpath)
    val jobflow = new Jobflow("flowId", ClassDescription.of(classOf[SparkClientCompilerSpec]), graph)

    compiler.process(jpContext, jobflow)

    val cl = Thread.currentThread.getContextClassLoader
    try {
      val classloader = new URLClassLoader(Array(classpath.toURI.toURL), cl)
      Thread.currentThread.setContextClassLoader(classloader)
      val cls = Class.forName("com.asakusafw.generated.spark.flowId.SparkClient", true, classloader)
        .asSubclass(classOf[SparkClient])
      val instance = cls.newInstance

      val conf = new SparkConf()
      conf.setAppName("AsakusaSparkClient")
      conf.setMaster("local[*]")

      val stageInfo = new StageInfo(
        sys.props("user.name"), "batchId", "flowId", null, "executionId", Map.empty[String, String])
      conf.setHadoopConf(Props.StageInfo, stageInfo.serialize)

      instance.execute(conf)
    } finally {
      Thread.currentThread.setContextClassLoader(cl)
    }

    spark { sc =>
      {
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        TemporaryInputFormat.setInputPaths(job, Seq(new Path(path, s"result/*/part-*")))
        val result = sc.newAPIHadoopRDD(
          job.getConfiguration,
          classOf[TemporaryInputFormat[Baa]],
          classOf[NullWritable],
          classOf[Baa]).map { case (_, baa) => (baa.id.get, baa.price.get) }.collect.toSeq.sortBy(_._1)
        assert(result.size === 2)
        assert(result(0)._1 === 0)
        assert(result(0)._2 === (0 until 10 by 2).map(_ * 100).sum)
        assert(result(1)._1 === 1)
        assert(result(1)._2 === (1 until 10 by 2).map(_ * 100).sum)
      }
    }
  }

  it should "compile Spark client with Summarize" in {
    val tmpDir = File.createTempFile("test-", null)
    tmpDir.delete
    val classpath = new File(tmpDir, "classes").getAbsoluteFile
    classpath.mkdirs()
    val path = new File(tmpDir, "tmp").getAbsolutePath

    spark { sc =>
      {
        val baa1 = sc.parallelize(0 until 500).map { i =>
          val baa = new Baa()
          baa.id.modify(i % 2)
          baa.price.modify(100 * i)
          baa
        }
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        job.setOutputKeyClass(classOf[NullWritable])
        job.setOutputValueClass(classOf[Baa])
        job.setOutputFormatClass(classOf[TemporaryOutputFormat[Baa]])
        TemporaryOutputFormat.setOutputPath(job, new Path(path, "extenal/input/baa1"))
        baa1.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
      }
      {
        val baa2 = sc.parallelize(500 until 1000).map { i =>
          val baa = new Baa()
          baa.id.modify(i % 2)
          baa.price.modify(100 * i)
          baa
        }
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        job.setOutputKeyClass(classOf[NullWritable])
        job.setOutputValueClass(classOf[Baa])
        job.setOutputFormatClass(classOf[TemporaryOutputFormat[Baa]])
        TemporaryOutputFormat.setOutputPath(job, new Path(path, "extenal/input/baa2"))
        baa2.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
      }
    }

    val baa1InputOperator = ExternalInput
      .newInstance("baa1/part-*",
        new ExternalInputInfo.Basic(
          ClassDescription.of(classOf[Baa]),
          "baa1",
          ClassDescription.of(classOf[Baa]),
          ExternalInputInfo.DataSize.UNKNOWN))

    val baa2InputOperator = ExternalInput
      .newInstance("baa2/part-*",
        new ExternalInputInfo.Basic(
          ClassDescription.of(classOf[Baa]),
          "baa2",
          ClassDescription.of(classOf[Baa]),
          ExternalInputInfo.DataSize.UNKNOWN))

    val summarizeOperator = OperatorExtractor
      .extract(classOf[Summarize], classOf[Ops], "summarize")
      .input("baas", ClassDescription.of(classOf[Baa]),
        new Group(
          Seq(PropertyName.of("id")),
          Seq.empty[Group.Ordering]),
        baa1InputOperator.getOperatorPort, baa2InputOperator.getOperatorPort)
      .output("result", ClassDescription.of(classOf[SummarizedBaa]))
      .build()

    val resultOutputOperator = ExternalOutput
      .newInstance("result", summarizeOperator.findOutput("result"))

    val graph = new OperatorGraph(Seq(
      baa1InputOperator, baa2InputOperator,
      summarizeOperator,
      resultOutputOperator))

    val compiler = new SparkClientCompiler {

      override def preparePlan(graph: OperatorGraph, flowId: String, dump: Boolean): Plan = {
        val plan = super.preparePlan(graph, flowId, true)
        assert(plan.getElements.size === 4)
        plan
      }
    }

    val jpContext = new MockJobflowProcessorContext(
      new CompilerOptions("buildid", path, Map.empty[String, String]),
      Thread.currentThread.getContextClassLoader,
      classpath)
    val jobflow = new Jobflow("flowId", ClassDescription.of(classOf[SparkClientCompilerSpec]), graph)

    compiler.process(jpContext, jobflow)

    val cl = Thread.currentThread.getContextClassLoader
    try {
      val classloader = new URLClassLoader(Array(classpath.toURI.toURL), cl)
      Thread.currentThread.setContextClassLoader(classloader)
      val cls = Class.forName("com.asakusafw.generated.spark.flowId.SparkClient", true, classloader)
        .asSubclass(classOf[SparkClient])
      val instance = cls.newInstance

      val conf = new SparkConf()
      conf.setAppName("AsakusaSparkClient")
      conf.setMaster("local[*]")

      val stageInfo = new StageInfo(
        sys.props("user.name"), "batchId", "flowId", null, "executionId", Map.empty[String, String])
      conf.setHadoopConf(Props.StageInfo, stageInfo.serialize)

      instance.execute(conf)
    } finally {
      Thread.currentThread.setContextClassLoader(cl)
    }

    spark { sc =>
      {
        val job = JobCompatibility.newJob(sc.hadoopConfiguration)
        TemporaryInputFormat.setInputPaths(job, Seq(new Path(path, s"result/*/part-*")))
        val result = sc.newAPIHadoopRDD(
          job.getConfiguration,
          classOf[TemporaryInputFormat[SummarizedBaa]],
          classOf[NullWritable],
          classOf[SummarizedBaa]).map {
            case (_, baa) =>
              (baa.id.get, baa.priceSum.get, baa.priceMax.get, baa.priceMin.get, baa.count.get)
          }.collect.toSeq.sortBy(_._1)
        assert(result.size === 2)
        assert(result(0)._1 === 0)
        assert(result(0)._2 === (0 until 1000 by 2).map(_ * 100).sum)
        assert(result(0)._3 === 99800)
        assert(result(0)._4 === 0)
        assert(result(0)._5 === 500)
        assert(result(1)._1 === 1)
        assert(result(1)._2 === (1 until 1000 by 2).map(_ * 100).sum)
        assert(result(1)._3 === 99900)
        assert(result(1)._4 === 100)
        assert(result(1)._5 === 500)
      }
    }
  }

  def spark[A](block: SparkContext => A): A = {
    val sc = new SparkContext(new SparkConf().setAppName("").setMaster("local[*]"))
    try {
      block(sc)
    } finally {
      sc.stop
    }
  }
}

object SparkClientCompilerSpec {

  class Hoge extends DataModel[Hoge] with Writable {

    val id = new IntOption()

    override def reset(): Unit = {
      id.setNull()
    }
    override def copyFrom(other: Hoge): Unit = {
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

  class Foo extends DataModel[Foo] with Writable {

    val id = new IntOption()
    val hogeId = new IntOption()

    override def reset(): Unit = {
      id.setNull()
      hogeId.setNull()
    }
    override def copyFrom(other: Foo): Unit = {
      id.copyFrom(other.id)
      hogeId.copyFrom(other.hogeId)
    }
    override def readFields(in: DataInput): Unit = {
      id.readFields(in)
      hogeId.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      id.write(out)
      hogeId.write(out)
    }

    def getIdOption: IntOption = id
    def getHogeIdOption: IntOption = hogeId
  }

  class Baa extends DataModel[Baa] with Writable {

    val id = new IntOption()
    val price = new IntOption()

    override def reset(): Unit = {
      id.setNull()
      price.setNull()
    }
    override def copyFrom(other: Baa): Unit = {
      id.copyFrom(other.id)
      price.copyFrom(other.price)
    }
    override def readFields(in: DataInput): Unit = {
      id.readFields(in)
      price.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      id.write(out)
      price.write(out)
    }

    def getIdOption: IntOption = id
    def getPriceOption: IntOption = price
  }

  @Summarized(term = new Summarized.Term(
    source = classOf[Baa],
    shuffle = new Key(group = Array("id")),
    foldings = Array(
      new Summarized.Folding(source = "id", destination = "id", aggregator = Summarized.Aggregator.ANY),
      new Summarized.Folding(source = "price", destination = "priceSum", aggregator = Summarized.Aggregator.SUM),
      new Summarized.Folding(source = "price", destination = "priceMax", aggregator = Summarized.Aggregator.MAX),
      new Summarized.Folding(source = "price", destination = "priceMin", aggregator = Summarized.Aggregator.MIN),
      new Summarized.Folding(source = "id", destination = "count", aggregator = Summarized.Aggregator.COUNT))))
  class SummarizedBaa extends DataModel[SummarizedBaa] with Writable {

    val id = new IntOption()
    val priceSum = new LongOption()
    val priceMax = new IntOption()
    val priceMin = new IntOption()
    val count = new LongOption()

    override def reset(): Unit = {
      id.setNull()
      priceSum.setNull()
      priceMax.setNull()
      priceMin.setNull()
      count.setNull()
    }
    override def copyFrom(other: SummarizedBaa): Unit = {
      id.copyFrom(other.id)
      priceSum.copyFrom(other.priceSum)
      priceMax.copyFrom(other.priceMax)
      priceMin.copyFrom(other.priceMin)
      count.copyFrom(other.count)
    }
    override def readFields(in: DataInput): Unit = {
      id.readFields(in)
      priceSum.readFields(in)
      priceMax.readFields(in)
      priceMin.readFields(in)
      count.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      id.write(out)
      priceSum.write(out)
      priceMax.write(out)
      priceMin.write(out)
      count.write(out)
    }

    def getIdOption: IntOption = id
    def getPriceSumOption: LongOption = priceSum
    def getPriceMaxOption: IntOption = priceMax
    def getPriceMinOption: IntOption = priceMin
    def getCountOption: LongOption = count
  }

  class Ops {

    @Extract
    def extract(hoge: Hoge, evenResult: Result[Hoge], oddResult: Result[Hoge]): Unit = {
      if (hoge.id.get % 2 == 0) {
        evenResult.add(hoge)
      } else {
        oddResult.add(hoge)
      }
    }

    @CoGroup
    def cogroup(
      hogeList: JList[Hoge], fooList: JList[Foo],
      hogeResult: Result[Hoge], fooResult: Result[Foo],
      hogeError: Result[Hoge], fooError: Result[Foo]): Unit = {
      if (hogeList.size == 1 && fooList.size == 1) {
        hogeResult.add(hogeList(0))
        fooResult.add(fooList(0))
      } else {
        hogeList.foreach(hogeError.add)
        fooList.foreach(fooError.add)
      }
    }

    @MasterCheck
    def mastercheck(hoge: Hoge, foo: Foo): Boolean = ???

    @Fold(partialAggregation = PartialAggregation.PARTIAL)
    def fold(acc: Baa, each: Baa): Unit = {
      acc.price.add(each.price)
    }

    @Summarize
    def summarize(value: Baa): SummarizedBaa = ???
  }
}
