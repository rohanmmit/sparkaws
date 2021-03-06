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

package org.apache.spark.sql.execution


import org.apache.spark.rdd.{RDD, ShuffledRDD}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Literal
import org.apache.spark.sql.catalyst.plans.physical.HashPartitioning
import org.scalatest.BeforeAndAfterAll

import org.apache.spark._
import org.apache.spark.sql._
import org.apache.spark.sql.execution.exchange.{ExchangeCoordinator, ShuffleExchange}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.test.TestSQLContext

import scala.collection.mutable.ArrayBuffer

class ExchangeCoordinatorSuite extends SparkFunSuite with BeforeAndAfterAll {

  private var originalActiveSQLContext: Option[SQLContext] = _
  private var originalInstantiatedSQLContext: Option[SQLContext] = _

  override protected def beforeAll(): Unit = {
    originalActiveSQLContext = SQLContext.getActive()
    originalInstantiatedSQLContext = SQLContext.getInstantiatedContextOption()

    SQLContext.clearActive()
    SQLContext.clearInstantiatedContext()
  }

  override protected def afterAll(): Unit = {
    // Set these states back.
    originalActiveSQLContext.foreach(ctx => SQLContext.setActive(ctx))
    originalInstantiatedSQLContext.foreach(ctx => SQLContext.setInstantiatedContext(ctx))
  }

  private def checkEstimation(
      coordinator: ExchangeCoordinator,
      bytesByPartitionIdArray: Array[Array[Long]],
      expectedPartitionStartIndices: Array[Int]): Unit = {
    val mapOutputStatistics = bytesByPartitionIdArray.zipWithIndex.map {
      case (bytesByPartitionId, index) =>
        new MapOutputStatistics(index, bytesByPartitionId)
    }
    val estimatedPartitionStartIndices =
      coordinator.estimatePartitionStartIndices(mapOutputStatistics)
    assert(estimatedPartitionStartIndices === expectedPartitionStartIndices)
  }



  test("test estimatePartitionStartIndices - 1 Exchange") {
    val test = { sqlContext: SQLContext =>
      {
        // All bytes per partition are 0.
        val bytesByPartitionId = Array[Long](0, 0, 0, 0, 0)
        val expectedPartitionStartIndices = Array[Int](0)
        checkEstimation(new ExchangeCoordinator(1, 100L, sqlContext), Array(bytesByPartitionId), expectedPartitionStartIndices)
      }

      {
        // Some bytes per partition are 0 and total size is less than the target size.
        // 1 post-shuffle partition is needed.
        val bytesByPartitionId = Array[Long](10, 0, 20, 0, 0)
        val expectedPartitionStartIndices = Array[Int](0)
        checkEstimation(new ExchangeCoordinator(1, 100L, sqlContext), Array(bytesByPartitionId), expectedPartitionStartIndices)
      }

      {
        // 2 post-shuffle partitions are needed.
        val bytesByPartitionId = Array[Long](10, 0, 90, 20, 0)
        val expectedPartitionStartIndices = Array[Int](0, 3)
        checkEstimation(new ExchangeCoordinator(1, 100L, sqlContext), Array(bytesByPartitionId), expectedPartitionStartIndices)
      }

      {
        // There are a few large pre-shuffle partitions.
        val bytesByPartitionId = Array[Long](110, 10, 100, 110, 0)
        val expectedPartitionStartIndices = Array[Int](0, 1, 3, 4)
        checkEstimation(new ExchangeCoordinator(1, 100L, sqlContext), Array(bytesByPartitionId), expectedPartitionStartIndices)
      }

      {
        // All pre-shuffle partitions are larger than the targeted size.
        val bytesByPartitionId = Array[Long](100, 110, 100, 110, 110)
        val expectedPartitionStartIndices = Array[Int](0, 1, 2, 3, 4)
        checkEstimation(new ExchangeCoordinator(1, 100L, sqlContext), Array(bytesByPartitionId), expectedPartitionStartIndices)
      }

      {
        // The last pre-shuffle partition is in a single post-shuffle partition.
        val bytesByPartitionId = Array[Long](30, 30, 0, 40, 110)
        val expectedPartitionStartIndices = Array[Int](0, 4)
        checkEstimation(new ExchangeCoordinator(1, 100L, sqlContext), Array(bytesByPartitionId), expectedPartitionStartIndices)
      }
    }
    withSQLContextBasic(test)
  }

  test("test estimatePartitionStartIndices - 2 Exchanges") {
    val test = { sqlContext: SQLContext =>
      val coordinator = new ExchangeCoordinator(2, 100L, sqlContext)

      {
        // If there are multiple values of the number of pre-shuffle partitions,
        // we should see an assertion error.
        val bytesByPartitionId1 = Array[Long](0, 0, 0, 0, 0)
        val bytesByPartitionId2 = Array[Long](0, 0, 0, 0, 0, 0)
        val mapOutputStatistics =
          Array(
            new MapOutputStatistics(0, bytesByPartitionId1),
            new MapOutputStatistics(1, bytesByPartitionId2))
        intercept[AssertionError](coordinator.estimatePartitionStartIndices(mapOutputStatistics))
      }

      {
        // All bytes per partition are 0.
        val bytesByPartitionId1 = Array[Long](0, 0, 0, 0, 0)
        val bytesByPartitionId2 = Array[Long](0, 0, 0, 0, 0)
        val expectedPartitionStartIndices = Array[Int](0)
        checkEstimation(
          coordinator,
          Array(bytesByPartitionId1, bytesByPartitionId2),
          expectedPartitionStartIndices)
      }

      {
        // Some bytes per partition are 0.
        // 1 post-shuffle partition is needed.
        val bytesByPartitionId1 = Array[Long](0, 10, 0, 20, 0)
        val bytesByPartitionId2 = Array[Long](30, 0, 20, 0, 20)
        val expectedPartitionStartIndices = Array[Int](0)
        checkEstimation(
          coordinator,
          Array(bytesByPartitionId1, bytesByPartitionId2),
          expectedPartitionStartIndices)
      }

      {
        // 2 post-shuffle partition are needed.
        val bytesByPartitionId1 = Array[Long](0, 10, 0, 20, 0)
        val bytesByPartitionId2 = Array[Long](30, 0, 70, 0, 30)
        val expectedPartitionStartIndices = Array[Int](0, 3)
        checkEstimation(
          coordinator,
          Array(bytesByPartitionId1, bytesByPartitionId2),
          expectedPartitionStartIndices)
      }

      {
        // 2 post-shuffle partition are needed.
        val bytesByPartitionId1 = Array[Long](0, 99, 0, 20, 0)
        val bytesByPartitionId2 = Array[Long](30, 0, 70, 0, 30)
        val expectedPartitionStartIndices = Array[Int](0, 2)
        checkEstimation(
          coordinator,
          Array(bytesByPartitionId1, bytesByPartitionId2),
          expectedPartitionStartIndices)
      }

      {
        // 2 post-shuffle partition are needed.
        val bytesByPartitionId1 = Array[Long](0, 100, 0, 30, 0)
        val bytesByPartitionId2 = Array[Long](30, 0, 70, 0, 30)
        val expectedPartitionStartIndices = Array[Int](0, 2, 4)
        checkEstimation(
          coordinator,
          Array(bytesByPartitionId1, bytesByPartitionId2),
          expectedPartitionStartIndices)
      }

      {
        // There are a few large pre-shuffle partitions.
        val bytesByPartitionId1 = Array[Long](0, 100, 40, 30, 0)
        val bytesByPartitionId2 = Array[Long](30, 0, 60, 0, 110)

        val expectedPartitionStartIndices = Array[Int](0, 2, 3)
        checkEstimation(
          coordinator,
          Array(bytesByPartitionId1, bytesByPartitionId2),
          expectedPartitionStartIndices)
      }

      {
        // All pairs of pre-shuffle partitions are larger than the targeted size.
        val bytesByPartitionId1 = Array[Long](100, 100, 40, 30, 0)
        val bytesByPartitionId2 = Array[Long](30, 0, 60, 70, 110)
        val expectedPartitionStartIndices = Array[Int](0, 1, 2, 3, 4)
        checkEstimation(
          coordinator,
          Array(bytesByPartitionId1, bytesByPartitionId2),
          expectedPartitionStartIndices)
      }
    }
    withSQLContextBasic(test)
  }

  test("test estimatePartitionStartIndices and enforce minimal number of reducers") {
    val test = { sqlContext: SQLContext =>
      val coordinator = new ExchangeCoordinator(2, 100L, sqlContext, Some(2))

      {
        // The minimal number of post-shuffle partitions is not enforced because
        // the size of data is 0.
        val bytesByPartitionId1 = Array[Long](0, 0, 0, 0, 0)
        val bytesByPartitionId2 = Array[Long](0, 0, 0, 0, 0)
        val expectedPartitionStartIndices = Array[Int](0)
        checkEstimation(
          coordinator,
          Array(bytesByPartitionId1, bytesByPartitionId2),
          expectedPartitionStartIndices)
      }

      {
        // The minimal number of post-shuffle partitions is enforced.
        val bytesByPartitionId1 = Array[Long](10, 5, 5, 0, 20)
        val bytesByPartitionId2 = Array[Long](5, 10, 0, 10, 5)
        val expectedPartitionStartIndices = Array[Int](0, 3)
        checkEstimation(
          coordinator,
          Array(bytesByPartitionId1, bytesByPartitionId2),
          expectedPartitionStartIndices)
      }

      {
        // The number of post-shuffle partitions is determined by the coordinator.
        val bytesByPartitionId1 = Array[Long](10, 50, 20, 80, 20)
        val bytesByPartitionId2 = Array[Long](40, 10, 0, 10, 30)
        val expectedPartitionStartIndices = Array[Int](0, 2, 4)
        checkEstimation(
          coordinator,
          Array(bytesByPartitionId1, bytesByPartitionId2),
          expectedPartitionStartIndices)
      }
    }
    withSQLContextBasic(test)
  }
  private def checkEstimationForBroadcast(coordinator: ExchangeCoordinator,
                                          bytesByPartitionIdArray: Array[Array[Long]],
                                          shuffleDependency1: ShuffleDependency[Int, InternalRow, InternalRow],
                                          shuffleDependency2: ShuffleDependency[Int, InternalRow, InternalRow],
                                          expectedPostRDDS: Map[ShuffleExchange, ShuffledRowRDD]): Unit = {
    val mapOutputStatistics = bytesByPartitionIdArray.zipWithIndex.map {
      case (bytesByPartitionId, index) =>
        new MapOutputStatistics(index, bytesByPartitionId)
    }
    for ((shuffleExchange,expectedShuffleRowRDD) <- expectedPostRDDS) {
      coordinator.registerExchange(shuffleExchange)
    }
    val shuffleDependencies = ArrayBuffer[ShuffleDependency[Int, InternalRow, InternalRow]]()
    shuffleDependencies += shuffleDependency1
    shuffleDependencies += shuffleDependency2
    coordinator.analyzeMapOutputStatistics(mapOutputStatistics, shuffleDependencies)
    for ((shuffleExchange,expectedShuffleRowRDD) <- expectedPostRDDS) {
      val shuffledRowRDD = coordinator.postShuffleRDD(shuffleExchange)
      assert(shuffledRowRDD.getNumPartitions == expectedShuffleRowRDD.getNumPartitions)
      val expectedPartitions = expectedShuffleRowRDD.getPartitions
      val actualPartitions = shuffledRowRDD.getPartitions
      for ((expectedPartition, actualPartition) <- expectedPartitions zip actualPartitions) {
        val expectedShuffledPartition = expectedPartition.asInstanceOf[ShuffledRowRDDPartition]
        val actualShuffledPartition = actualPartition.asInstanceOf[ShuffledRowRDDPartition]
        assert(expectedShuffledPartition.postShufflePartitionIndex == actualShuffledPartition.postShufflePartitionIndex)
        assert(expectedShuffledPartition.mapTaskId == actualShuffledPartition.mapTaskId)
        assert(expectedShuffledPartition.startPreShufflePartitionIndex == actualShuffledPartition.startPreShufflePartitionIndex)
        assert(expectedShuffledPartition.endPreShufflePartitionIndex == actualShuffledPartition.endPreShufflePartitionIndex)
      }
      assert(shuffledRowRDD.collect().deep == expectedShuffleRowRDD.collect().deep)
    }
  }

  test("test broadcast optimization stuff") {
    val finalPartitioning = HashPartitioning(Literal(1) :: Nil, 8)
    val plan1 = DummySparkPlan(outputPartitioning =  finalPartitioning)
    val plan2 =  DummySparkPlan()
    val test = { sqlContext: SQLContext =>
      val partitioning = HashPartitioning(Literal(1) :: Nil, 5)
      val row1 = InternalRow(Row("Row1", 555))
      val row2 = InternalRow(Row("Row2", 666))
      val rowsRDD1 = sqlContext.sparkContext.parallelize(Seq((0, row1), (1, row1), (2,row1),(3,row1),(4,row1),(5,row1),
          (6,row1)),6)
        .asInstanceOf[RDD[Product2[Int, InternalRow]]]
      val shuffleDependency1 =
        new ShuffleDependency[Int, InternalRow, InternalRow](
          rowsRDD1, new HashPartitioner(5))
      val rowsRDD2 = sqlContext.sparkContext.parallelize(Seq((0, row2), (1, row2), (2,row2),(3,row2)), 4)
        .asInstanceOf[RDD[Product2[Int, InternalRow]]]
      val shuffleDependency2 =
        new ShuffleDependency[Int, InternalRow, InternalRow](
          rowsRDD2, new HashPartitioner(5))

      {
        // testing that exchange1 is broadcast
        val bytesByPartitionId1 = Array[Long](10, 10, 10, 10, 10)
        val bytesByPartitionId2 = Array[Long](40, 10, 0, 10, 30)
        val shuffledRowRdd1 = new ShuffledRowRDD(shuffleDependency1, None,Some(true), Some(4))
        val shuffledRowRdd2 = new ShuffledRowRDD(shuffleDependency2, None,Some(false), Some(4))
        val coordinator = new ExchangeCoordinator(2, 100L, sqlContext, None, true)
        val shuffleExchange1 =  ShuffleExchange(partitioning,plan1, Some(coordinator));
        val shuffleExchange2 =  ShuffleExchange(partitioning,plan2, Some(coordinator));
        val postShuffledRdds = Map[ShuffleExchange, ShuffledRowRDD](shuffleExchange1 -> shuffledRowRdd1,
            shuffleExchange2 -> shuffledRowRdd2)
        checkEstimationForBroadcast(coordinator,
          Array(bytesByPartitionId1, bytesByPartitionId2),
          shuffleDependency1,
          shuffleDependency2,
          postShuffledRdds)
      }
      {
        // testing that exchange2 is broadcast
        val bytesByPartitionId1 = Array[Long](40, 40, 0, 10, 30)
        val bytesByPartitionId2 = Array[Long](10, 10, 10, 10, 10)
        val shuffledRowRdd1 = new ShuffledRowRDD(shuffleDependency1, None,Some(false), Some(6))
        val shuffledRowRdd2 = new ShuffledRowRDD(shuffleDependency2, None,Some(true), Some(6))
        val coordinator = new ExchangeCoordinator(2, 100L, sqlContext, None, true)
        val shuffleExchange1 =  ShuffleExchange(partitioning,plan1, Some(coordinator));
        val shuffleExchange2 =  ShuffleExchange(partitioning,plan2, Some(coordinator));
        val postShuffledRdds = Map[ShuffleExchange, ShuffledRowRDD](shuffleExchange1 -> shuffledRowRdd1,
              shuffleExchange2 -> shuffledRowRdd2)
        checkEstimationForBroadcast(coordinator,
          Array(bytesByPartitionId1, bytesByPartitionId2),
          shuffleDependency1,
          shuffleDependency2,
          postShuffledRdds)
      }
      {
      // testing that no exchange is broadcast
        val bytesByPartitionId1 = Array[Long](100, 100, 40, 30, 0)
        val bytesByPartitionId2 = Array[Long](30, 0, 60, 70, 110)
        val expectedPartitionStartIndices = Array[Int](0, 1, 2, 3, 4)
        val shuffledRowRdd1 = new ShuffledRowRDD(shuffleDependency1, Some(expectedPartitionStartIndices))
        val shuffledRowRdd2 = new ShuffledRowRDD(shuffleDependency2, Some(expectedPartitionStartIndices))
        val coordinator = new ExchangeCoordinator(2, 100L, sqlContext, None, true)
        val shuffleExchange1 =  ShuffleExchange(partitioning,plan1, Some(coordinator));
        val shuffleExchange2 =  ShuffleExchange(partitioning,plan2, Some(coordinator));
        val postShuffledRdds = Map[ShuffleExchange, ShuffledRowRDD](shuffleExchange1 -> shuffledRowRdd1,
              shuffleExchange2 -> shuffledRowRdd2)
        checkEstimationForBroadcast(coordinator,
          Array(bytesByPartitionId1, bytesByPartitionId2),
          shuffleDependency1,
          shuffleDependency2,
          postShuffledRdds)
      }

    }
    withSQLContextJoinOptimization(test, true, 100)
  }

  ///////////////////////////////////////////////////////////////////////////
  // Query tests
  ///////////////////////////////////////////////////////////////////////////

  val numInputPartitions: Int = 10

  def checkAnswer(actual: => DataFrame, expectedAnswer: Seq[Row]): Unit = {
    QueryTest.checkAnswer(actual, expectedAnswer) match {
      case Some(errorMessage) => fail(errorMessage)
      case None =>
    }
  }

  def getSparkConf(): SparkConf = {
      new SparkConf(false).setMaster("local[*]")
        .setAppName("test")
        .set("spark.ui.enabled", "false")
        .set("spark.driver.allowMultipleContexts", "true")
  }

  def withSQLContextBasic(f: SQLContext => Unit): Unit = {
    val sparkConf = getSparkConf()
    val sparkContext = new SparkContext(sparkConf)
    val sqlContext = new TestSQLContext(sparkContext)
    try f(sqlContext) finally sparkContext.stop()
  }

  def withSQLContext(
      f: SQLContext => Unit,
      targetNumPostShufflePartitions: Int,
      minNumPostShufflePartitions: Option[Int]): Unit = {
    val sparkConf = getSparkConf()
        .set(SQLConf.SHUFFLE_PARTITIONS.key, "5")
        .set(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "true")
        .set(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key, "-1")
        .set(
          SQLConf.SHUFFLE_TARGET_POSTSHUFFLE_INPUT_SIZE.key,
          targetNumPostShufflePartitions.toString)
    minNumPostShufflePartitions match {
      case Some(numPartitions) =>
        sparkConf.set(SQLConf.SHUFFLE_MIN_NUM_POSTSHUFFLE_PARTITIONS.key, numPartitions.toString)
      case None =>
        sparkConf.set(SQLConf.SHUFFLE_MIN_NUM_POSTSHUFFLE_PARTITIONS.key, "-1")
    }
    val sparkContext = new SparkContext(sparkConf)
    val sqlContext = new TestSQLContext(sparkContext)
    try f(sqlContext) finally sparkContext.stop()
  }

  // sql context for broadcast optimization in sort merge join
  def withSQLContextJoinOptimization(
                      f: SQLContext => Unit,
                      broadcastJoinEnabled: Boolean,
                      threshold: Int): Unit = {
    val sparkConf = getSparkConf()
      .set(SQLConf.SHUFFLE_PARTITIONS.key, "5")
      .set(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "true")
      .set(SQLConf.BROADCAST_OPTIMIZATION_ENABLED.key, broadcastJoinEnabled.toString)
      .set(SQLConf.BROADCAST_OPTIMIZATION_THRESHOLD.key,threshold.toString)
    val sparkContext = new SparkContext(sparkConf)
    val sqlContext = new TestSQLContext(sparkContext)
    try f(sqlContext) finally sparkContext.stop()
  }
  Seq(Some(3), None).foreach { minNumPostShufflePartitions =>
    val testNameNote = minNumPostShufflePartitions match {
      case Some(numPartitions) => "(minNumPostShufflePartitions: 3)"
      case None => ""
    }

    test(s"determining the number of reducers: aggregate operator$testNameNote") {
      val test = { sqlContext: SQLContext =>
        val df =
          sqlContext
            .range(0, 1000, 1, numInputPartitions)
            .selectExpr("id % 20 as key", "id as value")
        val agg = df.groupBy("key").count

        // Check the answer first.
        checkAnswer(
          agg,
          sqlContext.range(0, 20).selectExpr("id", "50 as cnt").collect())

        // Then, let's look at the number of post-shuffle partitions estimated
        // by the ExchangeCoordinator.
        val exchanges = agg.queryExecution.executedPlan.collect {
          case e: ShuffleExchange => e
        }
        assert(exchanges.length === 1)
        minNumPostShufflePartitions match {
          case Some(numPartitions) =>
            exchanges.foreach {
              case e: ShuffleExchange =>
                assert(e.coordinator.isDefined)
                assert(e.outputPartitioning.numPartitions === 3)
              case o =>
            }

          case None =>
            exchanges.foreach {
              case e: ShuffleExchange =>
                assert(e.coordinator.isDefined)
                assert(e.outputPartitioning.numPartitions === 2)
              case o =>
            }
        }
      }

      withSQLContext(test, 2000, minNumPostShufflePartitions)
    }

    test(s"determining the number of reducers: join operator$testNameNote") {
      val test = { sqlContext: SQLContext =>
        val df1 =
          sqlContext
            .range(0, 1000, 1, numInputPartitions)
            .selectExpr("id % 500 as key1", "id as value1")
        val df2 =
          sqlContext
            .range(0, 1000, 1, numInputPartitions)
            .selectExpr("id % 500 as key2", "id as value2")

        val join = df1.join(df2, col("key1") === col("key2")).select(col("key1"), col("value2"))

        // Check the answer first.
        val expectedAnswer =
          sqlContext
            .range(0, 1000)
            .selectExpr("id % 500 as key", "id as value")
            .unionAll(sqlContext.range(0, 1000).selectExpr("id % 500 as key", "id as value"))
        checkAnswer(
          join,
          expectedAnswer.collect())

        // Then, let's look at the number of post-shuffle partitions estimated
        // by the ExchangeCoordinator.
        val exchanges = join.queryExecution.executedPlan.collect {
          case e: ShuffleExchange => e
        }
        assert(exchanges.length === 2)
        minNumPostShufflePartitions match {
          case Some(numPartitions) =>
            exchanges.foreach {
              case e: ShuffleExchange =>
                assert(e.coordinator.isDefined)
                assert(e.outputPartitioning.numPartitions === 3)
              case o =>
            }

          case None =>
            exchanges.foreach {
              case e: ShuffleExchange =>
                assert(e.coordinator.isDefined)
                assert(e.outputPartitioning.numPartitions === 2)
              case o =>
            }
        }
      }

      withSQLContext(test, 16384, minNumPostShufflePartitions)
    }

    test(s"determining the number of reducers: complex query 1$testNameNote") {
      val test = { sqlContext: SQLContext =>
        val df1 =
          sqlContext
            .range(0, 1000, 1, numInputPartitions)
            .selectExpr("id % 500 as key1", "id as value1")
            .groupBy("key1")
            .count
            .toDF("key1", "cnt1")
        val df2 =
          sqlContext
            .range(0, 1000, 1, numInputPartitions)
            .selectExpr("id % 500 as key2", "id as value2")
            .groupBy("key2")
            .count
            .toDF("key2", "cnt2")

        val join = df1.join(df2, col("key1") === col("key2")).select(col("key1"), col("cnt2"))

        // Check the answer first.
        val expectedAnswer =
          sqlContext
            .range(0, 500)
            .selectExpr("id", "2 as cnt")
        checkAnswer(
          join,
          expectedAnswer.collect())

        // Then, let's look at the number of post-shuffle partitions estimated
        // by the ExchangeCoordinator.
        val exchanges = join.queryExecution.executedPlan.collect {
          case e: ShuffleExchange => e
        }
        assert(exchanges.length === 4)
        minNumPostShufflePartitions match {
          case Some(numPartitions) =>
            exchanges.foreach {
              case e: ShuffleExchange =>
                assert(e.coordinator.isDefined)
                assert(e.outputPartitioning.numPartitions === 3)
              case o =>
            }

          case None =>
            assert(exchanges.forall(_.coordinator.isDefined))
            assert(exchanges.map(_.outputPartitioning.numPartitions).toSeq.toSet === Set(1, 2))
        }
      }

      withSQLContext(test, 6644, minNumPostShufflePartitions)
    }

    test(s"determining the number of reducers: complex query 2$testNameNote") {
      val test = { sqlContext: SQLContext =>
        val df1 =
          sqlContext
            .range(0, 1000, 1, numInputPartitions)
            .selectExpr("id % 500 as key1", "id as value1")
            .groupBy("key1")
            .count
            .toDF("key1", "cnt1")
        val df2 =
          sqlContext
            .range(0, 1000, 1, numInputPartitions)
            .selectExpr("id % 500 as key2", "id as value2")

        val join =
          df1
            .join(df2, col("key1") === col("key2"))
            .select(col("key1"), col("cnt1"), col("value2"))

        // Check the answer first.
        val expectedAnswer =
          sqlContext
            .range(0, 1000)
            .selectExpr("id % 500 as key", "2 as cnt", "id as value")
        checkAnswer(
          join,
          expectedAnswer.collect())

        // Then, let's look at the number of post-shuffle partitions estimated
        // by the ExchangeCoordinator.
        val exchanges = join.queryExecution.executedPlan.collect {
          case e: ShuffleExchange => e
        }
        assert(exchanges.length === 3)
        minNumPostShufflePartitions match {
          case Some(numPartitions) =>
            exchanges.foreach {
              case e: ShuffleExchange =>
                assert(e.coordinator.isDefined)
                assert(e.outputPartitioning.numPartitions === 3)
              case o =>
            }

          case None =>
            assert(exchanges.forall(_.coordinator.isDefined))
            assert(exchanges.map(_.outputPartitioning.numPartitions).toSeq.toSet === Set(2, 3))
        }
      }

      withSQLContext(test, 6144, minNumPostShufflePartitions)
    }
  }
}
