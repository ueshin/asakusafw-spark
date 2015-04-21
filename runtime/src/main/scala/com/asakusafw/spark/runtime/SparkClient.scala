package com.asakusafw.spark.runtime

import org.apache.hadoop.conf.Configuration
import org.apache.spark._
import org.apache.spark.SparkContext._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD

import org.apache.spark.backdoor._
import org.apache.spark.util.backdoor._
import com.asakusafw.spark.runtime.driver.ShuffleKey
import com.asakusafw.spark.runtime.rdd._

abstract class SparkClient {

  def execute(conf: SparkConf): Int = {
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    conf.set("spark.kryo.registrator", kryoRegistrator)
    conf.set("spark.kryo.referenceTracking", false.toString)

    val sc = new SparkContext(conf)
    try {
      val hadoopConf = sc.broadcast(sc.hadoopConfiguration)
      execute(sc, hadoopConf)
    } finally {
      sc.stop()
    }
  }

  def execute(sc: SparkContext, hadoopConf: Broadcast[Configuration]): Int

  def kryoRegistrator: String

  def broadcastAsHash[V](
    sc: SparkContext,
    rdds: Seq[RDD[(ShuffleKey, V)]],
    directions: Seq[Boolean],
    part: Partitioner): Broadcast[Map[ShuffleKey, Seq[V]]] = {

    val name = "Prepare for Broadcast"
    sc.clearCallSite()
    sc.setCallSite(name)

    val ordering = Option(new ShuffleKey.SortOrdering(directions))
    val rdd = smcogroup(
      Seq((confluent(rdds, part, ordering).asInstanceOf[RDD[(ShuffleKey, _)]], ordering)),
      part,
      ShuffleKey.GroupingOrdering)
      .map { case (k, vs) => (k.dropOrdering, vs(0).toVector.asInstanceOf[Seq[V]]) }

    sc.setCallSite(CallSite(name, rdd.toDebugString))

    val results =
      sc.runJob(
        rdd,
        (iter: Iterator[(ShuffleKey, Seq[V])]) => iter.toVector,
        0 until rdd.partitions.size,
        allowLocal = true)
    sc.broadcast(results.flatten.toMap)
  }
}
