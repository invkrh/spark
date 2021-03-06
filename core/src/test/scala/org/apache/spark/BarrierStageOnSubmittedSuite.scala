/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark

import scala.concurrent.duration._
import scala.language.postfixOps

import org.scalatest.BeforeAndAfterEach

import org.apache.spark.rdd.{PartitionPruningRDD, RDD}
import org.apache.spark.scheduler.DAGScheduler
import org.apache.spark.util.ThreadUtils

/**
 * This test suite covers all the cases that shall fail fast on job submitted that contains one
 * of more barrier stages.
 */
class BarrierStageOnSubmittedSuite extends SparkFunSuite with BeforeAndAfterEach
    with LocalSparkContext {

  override def beforeEach(): Unit = {
    super.beforeEach()

    val conf = new SparkConf()
      .setMaster("local[4]")
      .setAppName("test")
    sc = new SparkContext(conf)
  }

  private def testSubmitJob(
      sc: SparkContext,
      rdd: RDD[Int],
      partitions: Option[Seq[Int]] = None,
      message: String): Unit = {
    val futureAction = sc.submitJob(
      rdd,
      (iter: Iterator[Int]) => iter.toArray,
      partitions.getOrElse(0 until rdd.partitions.length),
      { case (_, _) => return }: (Int, Array[Int]) => Unit,
      { return }
    )

    val error = intercept[SparkException] {
      ThreadUtils.awaitResult(futureAction, 5 seconds)
    }.getCause.getMessage
    assert(error.contains(message))
  }

  test("submit a barrier ResultStage that contains PartitionPruningRDD") {
    val prunedRdd = new PartitionPruningRDD(sc.parallelize(1 to 10, 4), index => index > 1)
    val rdd = prunedRdd
      .barrier()
      .mapPartitions((iter, context) => iter)
    testSubmitJob(sc, rdd,
      message = DAGScheduler.ERROR_MESSAGE_RUN_BARRIER_WITH_UNSUPPORTED_RDD_CHAIN_PATTERN)
  }

  test("submit a barrier ShuffleMapStage that contains PartitionPruningRDD") {
    val prunedRdd = new PartitionPruningRDD(sc.parallelize(1 to 10, 4), index => index > 1)
    val rdd = prunedRdd
      .barrier()
      .mapPartitions((iter, context) => iter)
      .repartition(2)
      .map(x => x + 1)
    testSubmitJob(sc, rdd,
      message = DAGScheduler.ERROR_MESSAGE_RUN_BARRIER_WITH_UNSUPPORTED_RDD_CHAIN_PATTERN)
  }

  test("submit a barrier stage that doesn't contain PartitionPruningRDD") {
    val prunedRdd = new PartitionPruningRDD(sc.parallelize(1 to 10, 4), index => index > 1)
    val rdd = prunedRdd
      .repartition(2)
      .barrier()
      .mapPartitions((iter, context) => iter)
    // Should be able to submit job and run successfully.
    val result = rdd.collect().sorted
    assert(result === Seq(6, 7, 8, 9, 10))
  }

  test("submit a barrier stage with partial partitions") {
    val rdd = sc.parallelize(1 to 10, 4)
      .barrier()
      .mapPartitions((iter, context) => iter)
    testSubmitJob(sc, rdd, Some(Seq(1, 3)),
      message = DAGScheduler.ERROR_MESSAGE_RUN_BARRIER_WITH_UNSUPPORTED_RDD_CHAIN_PATTERN)
  }

  test("submit a barrier stage with union()") {
    val rdd1 = sc.parallelize(1 to 10, 2)
      .barrier()
      .mapPartitions((iter, context) => iter)
    val rdd2 = sc.parallelize(1 to 20, 2)
    val rdd3 = rdd1
      .union(rdd2)
      .map(x => x * 2)
    // Fail the job on submit because the barrier RDD (rdd1) may be not assigned Task 0.
    testSubmitJob(sc, rdd3,
      message = DAGScheduler.ERROR_MESSAGE_RUN_BARRIER_WITH_UNSUPPORTED_RDD_CHAIN_PATTERN)
  }

  test("submit a barrier stage with coalesce()") {
    val rdd = sc.parallelize(1 to 10, 4)
      .barrier()
      .mapPartitions((iter, context) => iter)
      .coalesce(1)
    // Fail the job on submit because the barrier RDD requires to run on 4 tasks, but the stage
    // only launches 1 task.
    testSubmitJob(sc, rdd,
      message = DAGScheduler.ERROR_MESSAGE_RUN_BARRIER_WITH_UNSUPPORTED_RDD_CHAIN_PATTERN)
  }

  test("submit a barrier stage that contains an RDD that depends on multiple barrier RDDs") {
    val rdd1 = sc.parallelize(1 to 10, 4)
      .barrier()
      .mapPartitions((iter, context) => iter)
    val rdd2 = sc.parallelize(11 to 20, 4)
      .barrier()
      .mapPartitions((iter, context) => iter)
    val rdd3 = rdd1
      .zip(rdd2)
      .map(x => x._1 + x._2)
    testSubmitJob(sc, rdd3,
      message = DAGScheduler.ERROR_MESSAGE_RUN_BARRIER_WITH_UNSUPPORTED_RDD_CHAIN_PATTERN)
  }

  test("submit a barrier stage with zip()") {
    val rdd1 = sc.parallelize(1 to 10, 4)
      .barrier()
      .mapPartitions((iter, context) => iter)
    val rdd2 = sc.parallelize(11 to 20, 4)
    val rdd3 = rdd1
      .zip(rdd2)
      .map(x => x._1 + x._2)
    // Should be able to submit job and run successfully.
    val result = rdd3.collect().sorted
    assert(result === Seq(12, 14, 16, 18, 20, 22, 24, 26, 28, 30))
  }
}
