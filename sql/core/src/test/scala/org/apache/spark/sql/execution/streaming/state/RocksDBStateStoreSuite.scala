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

package org.apache.spark.sql.execution.streaming.state

import java.util.UUID

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

import org.apache.avro.AvroTypeException
import org.apache.hadoop.conf.Configuration
import org.scalatest.BeforeAndAfter
import org.scalatest.PrivateMethodTester
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar._

import org.apache.spark.{SparkConf, SparkException, SparkRuntimeException, SparkUnsupportedOperationException, TaskContext}
import org.apache.spark.io.CompressionCodec
import org.apache.spark.sql.LocalSparkSession.withSparkSession
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{GenericInternalRow, UnsafeProjection, UnsafeRow}
import org.apache.spark.sql.catalyst.util.quietly
import org.apache.spark.sql.execution.streaming.{StatefulOperatorStateInfo, StreamExecution}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._
import org.apache.spark.tags.ExtendedSQLTest
import org.apache.spark.unsafe.Platform
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.{ThreadUtils, Utils}

@ExtendedSQLTest
class RocksDBStateStoreSuite extends StateStoreSuiteBase[RocksDBStateStoreProvider]
  with AlsoTestWithEncodingTypes
  with AlsoTestWithRocksDBFeatures
  with PrivateMethodTester
  with SharedSparkSession
  with BeforeAndAfter
  with Matchers {

  // Helper method to get RocksDBStateStore using PrivateMethodTester
  private def getRocksDBStateStore(
      provider: RocksDBStateStoreProvider, version: Long): provider.RocksDBStateStore = {
    val getRocksDBStateStoreMethod =
      PrivateMethod[provider.RocksDBStateStore](Symbol("getRocksDBStateStore"))
    provider invokePrivate getRocksDBStateStoreMethod(version)
  }

  override def beforeEach(): Unit = {}
  override def afterEach(): Unit = {}

  before {
    StateStore.stop()
    require(!StateStore.isMaintenanceRunning)
    spark.streams.stateStoreCoordinator // initialize the lazy coordinator
  }

  after {
    StateStore.stop()
    require(!StateStore.isMaintenanceRunning)
  }

  import StateStoreTestsHelper._

  testWithColumnFamiliesAndEncodingTypes(s"version encoding",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    import RocksDBStateStoreProvider._

    tryWithProviderResource(newStoreProvider(colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)
      try {
        val keyRow = dataToKeyRow("a", 0)
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow)
        val iter = provider.rocksDB.iterator()
        assert(iter.hasNext)
        val kv = iter.next()

        // Verify the version encoded in first byte of the key and value byte arrays
        assert(Platform.getByte(kv.key, Platform.BYTE_ARRAY_OFFSET) === STATE_ENCODING_VERSION)
        assert(Platform.getByte(kv.value, Platform.BYTE_ARRAY_OFFSET) === STATE_ENCODING_VERSION)

        // The test verifies that the actual key-value pair (kv) matches these expected
        // byte patterns
        // exactly using sameElements, which ensures the serialization format remains consistent and
        // backward compatible. This is particularly important for state storage where the format
        // needs to be stable across Spark versions.
        val (expectedKey, expectedValue) = if (conf.stateStoreEncodingFormat == "avro") {
          (Array(0, 0, 0, 2, 2, 97, 2, 0), Array(0, 0, 0, 2, 2))
        } else {
          (Array(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 24, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 97, 0, 0, 0, 0, 0, 0, 0),
            Array(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0))
        }
        assert(kv.key.sameElements(expectedKey))
        assert(kv.value.sameElements(expectedValue))
      } finally {
        if (!store.hasCommitted) store.abort()
      }
    }
  }

  test("RocksDB confs are passed correctly from SparkSession to db instance") {
    val sparkConf = new SparkConf().setMaster("local").setAppName(this.getClass.getSimpleName)
    withSparkSession(SparkSession.builder().config(sparkConf).getOrCreate()) { spark =>
      // Set the session confs that should be passed into RocksDB
      val testConfs = Seq(
        ("spark.sql.streaming.stateStore.providerClass",
          classOf[RocksDBStateStoreProvider].getName),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".compactOnCommit", "true"),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".changelogCheckpointing.enabled", "true"),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".lockAcquireTimeoutMs", "10"),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".maxOpenFiles", "1000"),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".maxWriteBufferNumber", "3"),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".writeBufferSizeMB", "16"),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".allowFAllocate", "false"),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".compression", "zstd"),
        (SQLConf.STATE_STORE_ROCKSDB_FORMAT_VERSION.key, "4")
      )
      testConfs.foreach { case (k, v) => spark.conf.set(k, v) }

      // Prepare test objects for running task on state store
      val testRDD = spark.sparkContext.makeRDD[String](Seq("a"), 1)
      val testSchema = StructType(Seq(StructField("key", StringType, true)))
      val testStateInfo = StatefulOperatorStateInfo(
        checkpointLocation = Utils.createTempDir().getAbsolutePath,
        queryRunId = UUID.randomUUID, operatorId = 0, storeVersion = 0, numPartitions = 5, None)

      // Create state store in a task and get the RocksDBConf from the instantiated RocksDB instance
      val rocksDBConfInTask: RocksDBConf = testRDD.mapPartitionsWithStateStore[RocksDBConf](
        spark.sqlContext, testStateInfo, testSchema, testSchema,
        NoPrefixKeyStateEncoderSpec(testSchema)) {
          (store: StateStore, _: Iterator[String]) =>
            // Use reflection to get RocksDB instance
            val dbInstanceMethod =
              store.getClass.getMethods.filter(_.getName.contains("dbInstance")).head
            Iterator(dbInstanceMethod.invoke(store).asInstanceOf[RocksDB].conf)
        }.collect().head

      // Verify the confs are same as those configured in the session conf
      assert(rocksDBConfInTask.compactOnCommit == true)
      assert(rocksDBConfInTask.enableChangelogCheckpointing == true)
      assert(rocksDBConfInTask.lockAcquireTimeoutMs == 10L)
      assert(rocksDBConfInTask.formatVersion == 4)
      assert(rocksDBConfInTask.maxOpenFiles == 1000)
      assert(rocksDBConfInTask.maxWriteBufferNumber == 3)
      assert(rocksDBConfInTask.writeBufferSizeMB == 16L)
      assert(rocksDBConfInTask.allowFAllocate == false)
      assert(rocksDBConfInTask.compression == "zstd")
    }
  }

  testWithColumnFamiliesAndEncodingTypes("rocksdb file manager metrics exposed",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    import RocksDBStateStoreProvider._
    def getCustomMetric(metrics: StateStoreMetrics,
      customMetric: StateStoreCustomMetric): Long = {
      val metricPair = metrics.customMetrics.find(_._1.name == customMetric.name)
      assert(metricPair.isDefined)
      metricPair.get._2
    }

    withSQLConf(SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.key -> "1") {
      tryWithProviderResource(newStoreProvider(colFamiliesEnabled)) { provider =>
        val store = provider.getStore(0)
        // Verify state after updating
        put(store, "a", 0, 1)
        assert(get(store, "a", 0) === Some(1))
        assert(store.commit() === 1)
        provider.doMaintenance()
        assert(store.hasCommitted)
        val storeMetrics = store.metrics
        assert(storeMetrics.numKeys === 1)
        // SPARK-46249 - In the case of changelog checkpointing, the snapshot upload happens in
        // the context of the background maintenance thread. The file manager metrics are updated
        // here and will be available as part of the next metrics update. So we cannot rely on
        // the file manager metrics to be available here for this version.
        if (!isChangelogCheckpointingEnabled) {
          assert(getCustomMetric(storeMetrics, CUSTOM_METRIC_FILES_COPIED) > 0L)
          assert(getCustomMetric(storeMetrics, CUSTOM_METRIC_FILES_REUSED) == 0L)
          assert(getCustomMetric(storeMetrics, CUSTOM_METRIC_BYTES_COPIED) > 0L)
          assert(getCustomMetric(storeMetrics, CUSTOM_METRIC_ZIP_FILE_BYTES_UNCOMPRESSED) > 0L)
        }
      }
    }
  }

  testWithColumnFamiliesAndEncodingTypes("rocksdb range scan validation - invalid num columns",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    // zero ordering cols
    val ex1 = intercept[SparkUnsupportedOperationException] {
      tryWithProviderResource(newStoreProvider(keySchemaWithRangeScan,
        RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, Seq()),
        colFamiliesEnabled)) { provider =>
        provider.getStore(0)
      }
    }
    checkError(
      ex1,
      condition = "STATE_STORE_INCORRECT_NUM_ORDERING_COLS_FOR_RANGE_SCAN",
      parameters = Map(
        "numOrderingCols" -> "0"
      ),
      matchPVals = true
    )

    // ordering ordinals greater than schema cols
    val ex2 = intercept[SparkUnsupportedOperationException] {
      tryWithProviderResource(newStoreProvider(keySchemaWithRangeScan,
        RangeKeyScanStateEncoderSpec(
          keySchemaWithRangeScan,
          0.to(keySchemaWithRangeScan.length)),
        colFamiliesEnabled)) { provider =>
        provider.getStore(0)
      }
    }
    checkError(
      ex2,
      condition = "STATE_STORE_INCORRECT_NUM_ORDERING_COLS_FOR_RANGE_SCAN",
      parameters = Map(
        "numOrderingCols" -> (keySchemaWithRangeScan.length + 1).toString
      ),
      matchPVals = true
    )
  }

  testWithColumnFamiliesAndEncodingTypes("rocksdb range scan validation - variable sized columns",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    val keySchemaWithVariableSizeCols: StructType = StructType(
      Seq(StructField("key1", StringType, false), StructField("key2", StringType, false)))

    val ex = intercept[SparkUnsupportedOperationException] {
      tryWithProviderResource(newStoreProvider(keySchemaWithVariableSizeCols,
        RangeKeyScanStateEncoderSpec(keySchemaWithVariableSizeCols, Seq(0)),
        colFamiliesEnabled)) { provider =>
        provider.getStore(0)
      }
    }
    checkError(
      ex,
      condition = "STATE_STORE_VARIABLE_SIZE_ORDERING_COLS_NOT_SUPPORTED",
      parameters = Map(
        "fieldName" -> keySchemaWithVariableSizeCols.fields(0).name,
        "index" -> "0"
      ),
      matchPVals = true
    )
  }

  testWithColumnFamiliesAndEncodingTypes(
    "rocksdb range scan validation - variable size data types unsupported",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    val keySchemaWithSomeUnsupportedTypeCols: StructType = StructType(Seq(
      StructField("key1", StringType, false),
      StructField("key2", IntegerType, false),
      StructField("key3", FloatType, false),
      StructField("key4", BinaryType, false)
    ))
    val allowedRangeOrdinals = Seq(1, 2)

    keySchemaWithSomeUnsupportedTypeCols.fields.zipWithIndex.foreach { case (field, index) =>
      val isAllowed = allowedRangeOrdinals.contains(index)

      val getStore = () => {
        tryWithProviderResource(newStoreProvider(keySchemaWithSomeUnsupportedTypeCols,
            RangeKeyScanStateEncoderSpec(keySchemaWithSomeUnsupportedTypeCols, Seq(index)),
            colFamiliesEnabled)) { provider =>
            provider.getStore(0)
        }
      }

      if (isAllowed) {
        getStore()
      } else {
        val ex = intercept[SparkUnsupportedOperationException] {
          getStore()
        }
        checkError(
          ex,
          condition = "STATE_STORE_VARIABLE_SIZE_ORDERING_COLS_NOT_SUPPORTED",
          parameters = Map(
            "fieldName" -> field.name,
            "index" -> index.toString
          ),
          matchPVals = true
        )
      }
    }
  }

  testWithColumnFamiliesAndEncodingTypes("rocksdb range scan validation - null type columns",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    val keySchemaWithNullTypeCols: StructType = StructType(
      Seq(StructField("key1", NullType, false), StructField("key2", StringType, false)))

    val ex = intercept[SparkUnsupportedOperationException] {
      tryWithProviderResource(newStoreProvider(keySchemaWithNullTypeCols,
        RangeKeyScanStateEncoderSpec(keySchemaWithNullTypeCols, Seq(0)),
        colFamiliesEnabled)) { provider =>
        provider.getStore(0)
      }
    }
    checkError(
      ex,
      condition = "STATE_STORE_NULL_TYPE_ORDERING_COLS_NOT_SUPPORTED",
      parameters = Map(
        "fieldName" -> keySchemaWithNullTypeCols.fields(0).name,
        "index" -> "0"
      ),
      matchPVals = true
    )
  }

  testWithColumnFamiliesAndEncodingTypes("rocksdb range scan - fixed size non-ordering columns",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    tryWithProviderResource(newStoreProvider(keySchemaWithRangeScan,
      RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, Seq(0)),
      colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      // use non-default col family if column families are enabled
      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          keySchemaWithRangeScan, valueSchema,
          RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, Seq(0)))
      }

      val timerTimestamps = Seq(931L, 8000L, 452300L, 4200L, -1L, 90L, 1L, 2L, 8L,
        -230L, -14569L, -92L, -7434253L, 35L, 6L, 9L, -323L, 5L)
      timerTimestamps.foreach { ts =>
        // non-timestamp col is of fixed size
        val keyRow = dataToKeyRowWithRangeScan(ts, "a")
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store.get(keyRow, cfName)) === 1)
      }

      val result = store.iterator(cfName).map { kv =>
        val key = keyRowWithRangeScanToData(kv.key)
        key._1
      }.toSeq
      assert(result === timerTimestamps.sorted)
      store.commit()

      // test with a different set of power of 2 timestamps
      val store1 = provider.getStore(1)
      val timerTimestamps1 = Seq(-32L, -64L, -256L, 64L, 32L, 1024L, 4096L, 0L)
      timerTimestamps1.foreach { ts =>
        // non-timestamp col is of fixed size
        val keyRow = dataToKeyRowWithRangeScan(ts, "a")
        val valueRow = dataToValueRow(1)
        store1.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store1.get(keyRow, cfName)) === 1)
      }

      val result1 = store1.iterator(cfName).map { kv =>
        val key = keyRowWithRangeScanToData(kv.key)
        key._1
      }.toSeq
      assert(result1 === (timerTimestamps ++ timerTimestamps1).sorted)
      store1.commit()
    }
  }

  Seq(true, false).foreach { colFamiliesEnabled =>
    test(s"rocksdb range scan - variable size non-ordering columns with non-zero start ordinal " +
      s"with colFamiliesEnabled=$colFamiliesEnabled") {

      tryWithProviderResource(newStoreProvider(keySchema,
        RangeKeyScanStateEncoderSpec(
          keySchema, Seq(1)), colFamiliesEnabled)) { provider =>

        def getRandStr(): String = Random.alphanumeric.filter(_.isLetter)
          .take(Random.nextInt() % 10 + 1).mkString

        val store = provider.getStore(0)

        // use non-default col family if column families are enabled
        val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
        if (colFamiliesEnabled) {
          store.createColFamilyIfAbsent(cfName,
            keySchema, valueSchema,
            RangeKeyScanStateEncoderSpec(keySchema, Seq(1)))
        }

        val timerTimestamps = Seq(931, 8000, 452300, 4200, -1, 90, 1, 2, 8,
          -230, -14569, -92, -7434253, 35, 6, 9, -323, 5)
        timerTimestamps.foreach { ts =>
          val keyRow = dataToKeyRow(getRandStr(), ts)
          val valueRow = dataToValueRow(1)
          store.put(keyRow, valueRow, cfName)
          assert(valueRowToData(store.get(keyRow, cfName)) === 1)
        }

        val result = store.iterator(cfName).map { kv =>
          val key = keyRowToData(kv.key)
          key._2
        }.toSeq
        assert(result === timerTimestamps.sorted)
        store.commit()

        // test with a different set of power of 2 timestamps
        val store1 = provider.getStore(1)
        val timerTimestamps1 = Seq(-32, -64, -256, 64, 32, 1024, 4096, 0)
        timerTimestamps1.foreach { ts =>
          val keyRow = dataToKeyRow(getRandStr(), ts)
          val valueRow = dataToValueRow(1)
          store1.put(keyRow, valueRow, cfName)
          assert(valueRowToData(store1.get(keyRow, cfName)) === 1)
        }

        val result1 = store1.iterator(cfName).map { kv =>
          val key = keyRowToData(kv.key)
          key._2
        }.toSeq
        assert(result1 === (timerTimestamps ++ timerTimestamps1).sorted)
        store1.commit()
      }
    }
  }

  testWithColumnFamiliesAndEncodingTypes(
    "rocksdb range scan - variable size non-ordering columns with " +
    "double type values are supported",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    val testSchema: StructType = StructType(
      Seq(StructField("key1", DoubleType, false),
        StructField("key2", StringType, false)))

    val schemaProj = UnsafeProjection.create(Array[DataType](DoubleType, StringType))
    tryWithProviderResource(newStoreProvider(testSchema,
      RangeKeyScanStateEncoderSpec(testSchema, Seq(0)), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          testSchema, valueSchema,
          RangeKeyScanStateEncoderSpec(testSchema, Seq(0)))
      }

      // Verify that the sort ordering here is as follows:
      // -NaN, -Infinity, -ve values, -0, 0, +0, +ve values, +Infinity, +NaN
      val timerTimestamps: Seq[Double] = Seq(6894.32, 345.2795, -23.24, 24.466,
        7860.0, 4535.55, 423.42, -5350.355, 0.0, 0.001, 0.233, -53.255, -66.356, -244.452,
        96456466.3536677, 14421434453.43524562, Double.NaN, Double.PositiveInfinity,
        Double.NegativeInfinity, -Double.NaN, +0.0, -0.0,
        // A different representation of NaN than the Java constants
        java.lang.Double.longBitsToDouble(0x7ff80abcdef54321L),
        java.lang.Double.longBitsToDouble(0xfff80abcdef54321L))
      timerTimestamps.foreach { ts =>
        // non-timestamp col is of variable size
        val keyRow = schemaProj.apply(new GenericInternalRow(Array[Any](ts,
          UTF8String.fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString))))
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store.get(keyRow, cfName)) === 1)
      }

      // We expect to find NaNs at the beginning and end of the sorted list
      var nanIndexSet = Set(0, 1, timerTimestamps.size - 2, timerTimestamps.size - 1)
      val result = store.iterator(cfName).zipWithIndex.map { case (kv, idx) =>
        val keyRow = kv.key
        val key = (keyRow.getDouble(0), keyRow.getString(1))
        if (key._1.isNaN) {
          assert(nanIndexSet.contains(idx))
          nanIndexSet -= idx
        }
        key._1
      }.toSeq

      assert(nanIndexSet.isEmpty)
      assert(result.filter(!_.isNaN) === timerTimestamps.sorted.filter(!_.isNaN))
      store.commit()
    }
  }

  testWithColumnFamiliesAndEncodingTypes("rocksdb range scan - variable size non-ordering columns",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    tryWithProviderResource(newStoreProvider(keySchemaWithRangeScan,
      RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, Seq(0)),
      colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          keySchemaWithRangeScan, valueSchema,
          RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, Seq(0)))
      }

      val timerTimestamps = Seq(931L, 8000L, 452300L, 4200L, 90L, 1L, 2L, 8L, 3L, 35L, 6L, 9L, 5L,
        -24L, -999L, -2L, -61L, -9808344L, -1020L)
      timerTimestamps.foreach { ts =>
        // non-timestamp col is of variable size
        val keyRow = dataToKeyRowWithRangeScan(ts,
          Random.alphanumeric.take(Random.nextInt(20) + 1).mkString)
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store.get(keyRow, cfName)) === 1)
      }

      val result = store.iterator(cfName).map { kv =>
        val key = keyRowWithRangeScanToData(kv.key)
        key._1
      }.toSeq
      assert(result === timerTimestamps.sorted)
      store.commit()

      // test with a different set of power of 2 timestamps
      val store1 = provider.getStore(1)
      val timerTimestamps1 = Seq(64L, 32L, 1024L, 4096L, 0L, -512L, -8192L, -16L)
      timerTimestamps1.foreach { ts =>
        // non-timestamp col is of fixed size
        val keyRow = dataToKeyRowWithRangeScan(ts,
          Random.alphanumeric.take(Random.nextInt(20) + 1).mkString)
        val valueRow = dataToValueRow(1)
        store1.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store1.get(keyRow, cfName)) === 1)
      }

      val result1 = store1.iterator(cfName).map { kv =>
        val key = keyRowWithRangeScanToData(kv.key)
        key._1
      }.toSeq
      assert(result1 === (timerTimestamps ++ timerTimestamps1).sorted)
      store1.commit()
    }
  }

  Seq(true, false).foreach { colFamiliesEnabled =>
    Seq(Seq(1, 2), Seq(2, 1)).foreach { sortIndexes =>
      test(s"rocksdb range scan multiple ordering columns - with non-zero start ordinal - " +
        s"variable size non-ordering columns with colFamiliesEnabled=$colFamiliesEnabled " +
        s"sortIndexes=${sortIndexes.mkString(",")}") {

        val testSchema: StructType = StructType(
          Seq(StructField("key1", StringType, false),
            StructField("key2", LongType, false),
            StructField("key3", IntegerType, false)))

        val schemaProj = UnsafeProjection.create(Array[DataType](StringType, LongType, IntegerType))

        tryWithProviderResource(newStoreProvider(testSchema,
          RangeKeyScanStateEncoderSpec(testSchema, sortIndexes), colFamiliesEnabled)) { provider =>
          val store = provider.getStore(0)

          val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
          if (colFamiliesEnabled) {
            store.createColFamilyIfAbsent(cfName,
              testSchema, valueSchema,
              RangeKeyScanStateEncoderSpec(testSchema, sortIndexes))
          }

          val timerTimestamps = Seq((931L, 10), (8000L, 40), (452300L, 1), (4200L, 68), (90L, 2000),
            (1L, 27), (1L, 394), (1L, 5), (3L, 980),
            (-1L, 232), (-1L, 3455), (-6109L, 921455), (-9808344L, 1), (-1020L, 2),
            (35L, 2112), (6L, 90118), (9L, 95118), (6L, 87210), (-4344L, 2323), (-3122L, 323))
          timerTimestamps.foreach { ts =>
            // order by long col first and then by int col
            val keyRow = schemaProj.apply(new GenericInternalRow(Array[Any](UTF8String
              .fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString), ts._1,
              ts._2)))
            val valueRow = dataToValueRow(1)
            store.put(keyRow, valueRow, cfName)
            assert(valueRowToData(store.get(keyRow, cfName)) === 1)
          }

          val result = store.iterator(cfName).map { kv =>
            val keyRow = kv.key
            (keyRow.getLong(1), keyRow.getInt(2))
          }.toSeq

          def getOrderedTs(
              orderedInput: Seq[(Long, Int)],
              sortIndexes: Seq[Int]): Seq[(Long, Int)] = {
            sortIndexes match {
              case Seq(1, 2) => orderedInput.sortBy(x => (x._1, x._2))
              case Seq(2, 1) => orderedInput.sortBy(x => (x._2, x._1))
              case _ => throw new IllegalArgumentException(s"Invalid sortIndexes: " +
                s"${sortIndexes.mkString(",")}")
            }
          }

          assert(result === getOrderedTs(timerTimestamps, sortIndexes))
          store.commit()
        }
      }
    }
  }

  testWithColumnFamiliesAndEncodingTypes(
    "rocksdb range scan multiple ordering columns - variable size " +
    s"non-ordering columns",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    val testSchema: StructType = StructType(
      Seq(StructField("key1", LongType, false),
        StructField("key2", IntegerType, false),
        StructField("key3", StringType, false)))

    val schemaProj = UnsafeProjection.create(Array[DataType](LongType, IntegerType, StringType))

    tryWithProviderResource(newStoreProvider(testSchema,
      RangeKeyScanStateEncoderSpec(testSchema, Seq(0, 1)), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          testSchema, valueSchema,
          RangeKeyScanStateEncoderSpec(testSchema, Seq(0, 1)))
      }

      val timerTimestamps = Seq((931L, 10), (8000L, 40), (452300L, 1), (4200L, 68), (90L, 2000),
        (1L, 27), (1L, 394), (1L, 5), (3L, 980),
        (-1L, 232), (-1L, 3455), (-6109L, 921455), (-9808344L, 1), (-1020L, 2),
        (35L, 2112), (6L, 90118), (9L, 95118), (6L, 87210), (-4344L, 2323), (-3122L, 323))
      timerTimestamps.foreach { ts =>
        // order by long col first and then by int col
        val keyRow = schemaProj.apply(new GenericInternalRow(Array[Any](ts._1, ts._2,
          UTF8String.fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString))))
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store.get(keyRow, cfName)) === 1)
      }

      val result = store.iterator(cfName).map { kv =>
        val keyRow = kv.key
        val key = (keyRow.getLong(0), keyRow.getInt(1), keyRow.getString(2))
        (key._1, key._2)
      }.toSeq
      assert(result === timerTimestamps.sorted)
    }
  }

  test("AvroStateEncoder - add field") {
    val keySchema = StructType(Seq(
      StructField("k", StringType)
    ))

    val initialValueSchema = StructType(Seq(
      StructField("value", IntegerType, true)
    ))

    val evolvedValueSchema = StructType(Seq(
      StructField("value", IntegerType, true),
      StructField("timestamp", LongType, true)
    ))

    // Create test state schema provider
    val testProvider = new TestStateSchemaProvider()

    // Add initial schema version
    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      initialValueSchema,
      valueSchemaId = 0
    )

    // Create encoder with initial schema
    val encoder1 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      initialValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    // Create test data
    val proj = UnsafeProjection.create(initialValueSchema)
    val row1 = proj.apply(InternalRow(1))

    // Encode with schema v0
    val encoded = encoder1.encodeValue(row1)

    // Add evolved schema
    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      evolvedValueSchema,
      valueSchemaId = 1
    )

    // Create encoder with initial schema
    val encoder2 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      evolvedValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    // Decode with evolved schema
    val decoded = encoder2.decodeValue(encoded)

    // Should be able to read old format
    assert(decoded.getInt(0) === 1)
    assert(decoded.isNullAt(1)) // New field should be null

    // Encode with new schema
    val proj2 = UnsafeProjection.create(evolvedValueSchema)
    val row2 = proj2.apply(InternalRow(2, 100L))
    val encoded2 = encoder2.encodeValue(row2)

    // Should write with new schema version
    val decoded2 = encoder2.decodeValue(encoded2)
    assert(decoded2.getInt(0) === 2)
    assert(decoded2.getLong(1) === 100L)
  }

  test("AvroStateEncoder - add field with null") {
    val keySchema = StructType(Seq(
      StructField("k", StringType)
    ))

    val initialValueSchema = StructType(Seq(
      StructField("value", IntegerType) // Made nullable
    ))

    val evolvedValueSchema = StructType(Seq(
      StructField("value", IntegerType), // Made nullable
      StructField("timestamp", LongType)
    ))

    // Create test state schema provider
    val testProvider = new TestStateSchemaProvider()

    // Add initial schema version
    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      initialValueSchema,
      valueSchemaId = 0
    )

    // Create encoder with initial schema
    val encoder1 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      initialValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    // Create test data with null value
    val proj = UnsafeProjection.create(initialValueSchema)
    val row1 = proj.apply(InternalRow(null))

    // Encode with schema v0
    val encoded = encoder1.encodeValue(row1)

    // Read back the encoded value to verify null handling
    val decodedWithOriginalSchema = encoder1.decodeValue(encoded)
    assert(decodedWithOriginalSchema.isNullAt(0))

    // Add evolved schema
    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      evolvedValueSchema,
      valueSchemaId = 1
    )

    // Create encoder with evolved schema
    val encoder2 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      evolvedValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    // Decode original value with evolved schema
    val decoded = encoder2.decodeValue(encoded)

    // Should be able to read old format with null value
    assert(decoded.isNullAt(0))
    assert(decoded.isNullAt(1)) // New field should be null

    // Encode with new schema, including null value
    val proj2 = UnsafeProjection.create(evolvedValueSchema)
    val row2 = proj2.apply(InternalRow(null, 100L))
    val encoded2 = encoder2.encodeValue(row2)

    // Should write with new schema version
    val decoded2 = encoder2.decodeValue(encoded2)
    assert(decoded2.isNullAt(0))
    assert(decoded2.getLong(1) === 100L)

    // Test mix of null and non-null values
    val row3 = proj2.apply(InternalRow(2, null))
    val encoded3 = encoder2.encodeValue(row3)
    val decoded3 = encoder2.decodeValue(encoded3)
    assert(decoded3.getInt(0) === 2)
    assert(decoded3.isNullAt(1))
  }

  test("AvroStateEncoder - remove field") {
    val keySchema = StructType(Seq(
      StructField("k", StringType)
    ))

    val initialValueSchema = StructType(Seq(
      StructField("value", IntegerType, true),
      StructField("timestamp", LongType, true)
    ))

    val evolvedValueSchema = StructType(Seq(
      StructField("value", IntegerType, true)
    ))

    val testProvider = new TestStateSchemaProvider()
    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      initialValueSchema,
      valueSchemaId = 0
    )

    val encoder1 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      initialValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    val proj = UnsafeProjection.create(initialValueSchema)
    val row1 = proj.apply(InternalRow(1, 100L))
    val encoded = encoder1.encodeValue(row1)

    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      evolvedValueSchema,
      valueSchemaId = 1
    )

    val encoder2 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      evolvedValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    val decoded = encoder2.decodeValue(encoded)
    assert(decoded.numFields() === 1)
    assert(decoded.getInt(0) === 1)

    val proj2 = UnsafeProjection.create(evolvedValueSchema)
    val row2 = proj2.apply(InternalRow(2))
    val encoded2 = encoder2.encodeValue(row2)

    val decoded2 = encoder2.decodeValue(encoded2)
    assert(decoded2.getInt(0) === 2)
  }

  test("AvroStateEncoder - nested struct evolution") {
    val keySchema = StructType(Seq(
      StructField("k", StringType)
    ))

    val initialValueSchema = StructType(Seq(
      StructField("metadata", StructType(Seq(
        StructField("id", IntegerType, true),
        StructField("name", StringType, true)
      )))
    ))

    val evolvedValueSchema = StructType(Seq(
      StructField("metadata", StructType(Seq(
        StructField("id", IntegerType, true),
        StructField("name", StringType, true),
        StructField("timestamp", LongType, true)
      )))
    ))

    val testProvider = new TestStateSchemaProvider()
    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      initialValueSchema,
      valueSchemaId = 0
    )

    val encoder1 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      initialValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    val proj = UnsafeProjection.create(initialValueSchema)
    val row1 = proj.apply(InternalRow(InternalRow(1, UTF8String.fromString("test"))))
    val encoded = encoder1.encodeValue(row1)

    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      evolvedValueSchema,
      valueSchemaId = 1
    )

    val encoder2 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      evolvedValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    val decoded = encoder2.decodeValue(encoded)
    assert(decoded.getStruct(0, 3).getInt(0) === 1)
    assert(decoded.getStruct(0, 3).getString(1) === "test")
    assert(decoded.getStruct(0, 3).isNullAt(2))

    val proj2 = UnsafeProjection.create(evolvedValueSchema)
    val row2 = proj2.apply(InternalRow(InternalRow(2, UTF8String.fromString("test2"), 100L)))
    val encoded2 = encoder2.encodeValue(row2)

    val decoded2 = encoder2.decodeValue(encoded2)
    assert(decoded2.getStruct(0, 3).getInt(0) === 2)
    assert(decoded2.getStruct(0, 3).getString(1) === "test2")
    assert(decoded2.getStruct(0, 3).getLong(2) === 100L)
  }

  test("AvroStateEncoder - add nested struct field") {
    val keySchema = StructType(Seq(
      StructField("k", StringType)
    ))

    val initialValueSchema = StructType(Seq(
      StructField("value", IntegerType, true)
    ))

    val evolvedValueSchema = StructType(Seq(
      StructField("value", IntegerType, true),
      StructField("metadata", StructType(Seq(
        StructField("id", IntegerType, true),
        StructField("count", IntegerType, true)
      )), true)
    ))

    // Create test state schema provider
    val testProvider = new TestStateSchemaProvider()

    // Add initial schema version
    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      initialValueSchema,
      valueSchemaId = 0
    )

    // Create encoder with initial schema
    val encoder1 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      initialValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    // Create test data
    val proj = UnsafeProjection.create(initialValueSchema)
    val row1 = proj.apply(InternalRow(1))

    // Encode with schema v0
    val encoded = encoder1.encodeValue(row1)

    // Add evolved schema
    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      evolvedValueSchema,
      valueSchemaId = 1
    )

    // Create encoder with evolved schema
    val encoder2 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      evolvedValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    // Decode with evolved schema
    val decoded = encoder2.decodeValue(encoded)

    // Should be able to read old format
    assert(decoded.getInt(0) === 1)
    assert(decoded.isNullAt(1)) // New nested struct field should be null

    // Encode with new schema including nested struct
    val proj2 = UnsafeProjection.create(evolvedValueSchema)
    val row2 = proj2.apply(InternalRow(2, InternalRow(10, 20)))
    val encoded2 = encoder2.encodeValue(row2)

    // Should write with new schema version
    val decoded2 = encoder2.decodeValue(encoded2)
    assert(decoded2.getInt(0) === 2)
    assert(!decoded2.isNullAt(1)) // Nested struct should not be null
    assert(decoded2.getStruct(1, 2).getInt(0) === 10)
    assert(decoded2.getStruct(1, 2).getInt(1) === 20)

    // Test with null nested struct
    val row3 = proj2.apply(InternalRow(3, null))
    val encoded3 = encoder2.encodeValue(row3)
    val decoded3 = encoder2.decodeValue(encoded3)
    assert(decoded3.getInt(0) === 3)
    assert(decoded3.isNullAt(1)) // Nested struct should be null
  }

  test("AvroStateEncoder - upcast field type") {
    val keySchema = StructType(Seq(
      StructField("k", StringType)
    ))

    // Initial schema with IntegerType
    val initialValueSchema = StructType(Seq(
      StructField("value", IntegerType, false)
    ))

    // Evolved schema with LongType (upcast)
    val evolvedValueSchema = StructType(Seq(
      StructField("value", LongType, false)
    ))

    val testProvider = new TestStateSchemaProvider()
    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      initialValueSchema,
      valueSchemaId = 0
    )

    val encoder1 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      initialValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    // Create and encode data with initial schema (IntegerType)
    val proj = UnsafeProjection.create(initialValueSchema)
    val row1 = proj.apply(InternalRow(42))
    val encoded = encoder1.encodeValue(row1)

    // Add evolved schema with LongType
    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      evolvedValueSchema,
      valueSchemaId = 1
    )

    val encoder2 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      evolvedValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    // Should successfully decode IntegerType as LongType
    val decoded = encoder2.decodeValue(encoded)
    assert(decoded.getLong(0) === 42L)

    // Write with new schema
    val proj2 = UnsafeProjection.create(evolvedValueSchema)
    val row2 = proj2.apply(InternalRow(9999999999L))  // Value too large for IntegerType
    val encoded2 = encoder2.encodeValue(row2)

    val decoded2 = encoder2.decodeValue(encoded2)
    assert(decoded2.getLong(0) === 9999999999L)
  }

  test("AvroStateEncoder - reorder fields") {
    val keySchema = StructType(Seq(
      StructField("k", StringType)
    ))

    // Initial schema with fields in original order
    val initialValueSchema = StructType(Seq(
      StructField("first", IntegerType, false),
      StructField("second", StringType, true),
      StructField("third", LongType, true)
    ))

    // Evolved schema with reordered fields
    val evolvedValueSchema = StructType(Seq(
      StructField("second", StringType, true),
      StructField("third", LongType, true),
      StructField("first", IntegerType, false)
    ))

    val testProvider = new TestStateSchemaProvider()
    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      initialValueSchema,
      valueSchemaId = 0
    )

    val encoder1 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      initialValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    // Create and encode data with initial order
    val proj = UnsafeProjection.create(initialValueSchema)
    val row1 = proj.apply(InternalRow(1, UTF8String.fromString("test"), 100L))
    val encoded = encoder1.encodeValue(row1)

    // Add evolved schema with reordered fields
    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      evolvedValueSchema,
      valueSchemaId = 1
    )

    val encoder2 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      evolvedValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    // Should decode with correct field values despite reordering
    val decoded = encoder2.decodeValue(encoded)
    assert(decoded.getString(0) === "test")  // second field now first
    assert(decoded.getLong(1) === 100L)      // third field now second
    assert(decoded.getInt(2) === 1)          // first field now third

    // Write with new field order
    val proj2 = UnsafeProjection.create(evolvedValueSchema)
    val row2 = proj2.apply(InternalRow(
      UTF8String.fromString("test2"),
      200L,
      2
    ))
    val encoded2 = encoder2.encodeValue(row2)

    val decoded2 = encoder2.decodeValue(encoded2)
    assert(decoded2.getString(0) === "test2")
    assert(decoded2.getLong(1) === 200L)
    assert(decoded2.getInt(2) === 2)
  }

  test("AvroStateEncoder - downcast field type throws exception on read") {
    val keySchema = StructType(Seq(
      StructField("k", StringType)
    ))

    // Initial schema with LongType
    val initialValueSchema = StructType(Seq(
      StructField("value", LongType, false)
    ))

    // Evolved schema with IntegerType (downcast - should fail)
    val evolvedValueSchema = StructType(Seq(
      StructField("value", IntegerType, false)
    ))

    val testProvider = new TestStateSchemaProvider()
    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      initialValueSchema,
      valueSchemaId = 0
    )

    val encoder1 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      initialValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    // Create and encode data with initial schema (LongType)
    val proj = UnsafeProjection.create(initialValueSchema)
    val row1 = proj.apply(InternalRow(9999999999L))  // Value too large for IntegerType
    val encoded = encoder1.encodeValue(row1)

    // Add evolved schema with IntegerType
    testProvider.captureSchema(
      StateStore.DEFAULT_COL_FAMILY_NAME,
      keySchema,
      evolvedValueSchema,
      valueSchemaId = 1
    )

    val encoder2 = new AvroStateEncoder(
      NoPrefixKeyStateEncoderSpec(keySchema),
      evolvedValueSchema,
      Some(testProvider),
      StateStore.DEFAULT_COL_FAMILY_NAME
    )

    // Attempting to decode Long as Int should fail
    val exception = intercept[AvroTypeException] {
      encoder2.decodeValue(encoded)
    }
    assert(exception.getMessage.contains("Found long, expecting union"))
  }

  Seq(Seq(0, 1, 2), Seq(0, 2, 1), Seq(2, 1, 0), Seq(2, 0, 1)).foreach { sortIndexes =>
    testWithColumnFamiliesAndEncodingTypes(
      s"rocksdb range scan multiple non-contiguous ordering columns " +
        s"and sortIndexes=${sortIndexes.mkString(",")}",
      TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
      val testSchema: StructType = StructType(
        Seq(
          StructField("ordering1", LongType, false),
          StructField("key2", StringType, false),
          StructField("ordering2", IntegerType, false),
          StructField("string2", StringType, false),
          StructField("ordering3", DoubleType, false)
        )
      )

      val testSchemaProj = UnsafeProjection.create(Array[DataType](
        immutable.ArraySeq.unsafeWrapArray(testSchema.fields.map(_.dataType)): _*))
      // Multiply by 2 to get the actual ordinals in the row
      val rangeScanOrdinals = sortIndexes.map(_ * 2)

      tryWithProviderResource(
        newStoreProvider(
          testSchema,
          RangeKeyScanStateEncoderSpec(testSchema, rangeScanOrdinals),
          colFamiliesEnabled
        )
      ) { provider =>
        val store = provider.getStore(0)

        val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
        if (colFamiliesEnabled) {
          store.createColFamilyIfAbsent(
            cfName,
            testSchema,
            valueSchema,
            RangeKeyScanStateEncoderSpec(testSchema, rangeScanOrdinals)
          )
        }

        val orderedInput = Seq(
          // Make sure that the first column takes precedence, even if the
          // later columns are greater
          (-2L, 0, 99.0),
          (-1L, 0, 98.0),
          (0L, 0, 97.0),
          (2L, 0, 96.0),
          // Make sure that the second column takes precedence, when the first
          // column is all the same
          (3L, -2, -1.0),
          (3L, -1, -2.0),
          (3L, 0, -3.0),
          (3L, 2, -4.0),
          // Finally, make sure that the third column takes precedence, when the
          // first two ordering columns are the same.
          (4L, -1, -127.0),
          (4L, -1, 0.0),
          (4L, -1, 64.0),
          (4L, -1, 127.0)
        )
        val scrambledInput = Random.shuffle(orderedInput)

        scrambledInput.foreach { record =>
          val keyRow = testSchemaProj.apply(
            new GenericInternalRow(
              Array[Any](
                record._1,
                UTF8String.fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString),
                record._2,
                UTF8String.fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString),
                record._3
              )
            )
          )

          // The value is just a "dummy" value of 1
          val valueRow = dataToValueRow(1)
          store.put(keyRow, valueRow, cfName)
          assert(valueRowToData(store.get(keyRow, cfName)) === 1)
        }

        val result = store
          .iterator(cfName)
          .map { kv =>
            val keyRow = kv.key
            (keyRow.getLong(0), keyRow.getInt(2), keyRow.getDouble(4))
          }
          .toSeq

        def getOrderedInput(
          orderedInput: Seq[(Long, Int, Double)],
          sortIndexes: Seq[Int]): Seq[(Long, Int, Double)] = {
          sortIndexes match {
            case Seq(0, 1, 2) => orderedInput.sortBy(x => (x._1, x._2, x._3))
            case Seq(0, 2, 1) => orderedInput.sortBy(x => (x._1, x._3, x._2))
            case Seq(2, 1, 0) => orderedInput.sortBy(x => (x._3, x._2, x._1))
            case Seq(2, 0, 1) => orderedInput.sortBy(x => (x._3, x._1, x._2))
            case _ => throw new IllegalArgumentException(s"Invalid sortIndexes: " +
              s"${sortIndexes.mkString(",")}")
          }
        }

        assert(result === getOrderedInput(orderedInput, sortIndexes))
        store.commit()
      }
    }
  }

  testWithColumnFamiliesAndEncodingTypes(
    "rocksdb range scan multiple ordering columns - variable size " +
    s"non-ordering columns with null values in first ordering column",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    val testSchema: StructType = StructType(
      Seq(StructField("key1", LongType, true),
        StructField("key2", IntegerType, true),
        StructField("key3", StringType, false)))

    val schemaProj = UnsafeProjection.create(Array[DataType](LongType, IntegerType, StringType))

    tryWithProviderResource(newStoreProvider(testSchema,
      RangeKeyScanStateEncoderSpec(testSchema, Seq(0, 1)), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)
      try {
        val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
        if (colFamiliesEnabled) {
          store.createColFamilyIfAbsent(cfName,
            testSchema, valueSchema,
            RangeKeyScanStateEncoderSpec(testSchema, Seq(0, 1)))
        }

        val timerTimestamps = Seq((931L, 10), (null, 40), (452300L, 1),
          (4200L, 68), (90L, 2000), (1L, 27), (1L, 394), (1L, 5), (3L, 980), (35L, 2112),
          (6L, 90118), (9L, 95118), (6L, 87210), (null, 113), (null, 28),
          (null, -23), (null, -5534), (-67450L, 2434), (-803L, 3422))
        timerTimestamps.foreach { ts =>
          // order by long col first and then by int col
          val keyRow = schemaProj.apply(new GenericInternalRow(Array[Any](ts._1, ts._2,
            UTF8String.fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString))))
          val valueRow = dataToValueRow(1)
          store.put(keyRow, valueRow, cfName)
          assert(valueRowToData(store.get(keyRow, cfName)) === 1)
        }

        // verify that the expected null cols are seen
        val nullRows = store.iterator(cfName).filter { kv =>
          val keyRow = kv.key
          keyRow.isNullAt(0)
        }
        assert(nullRows.size === 5)

        // filter out the null rows and verify the rest
        val result: Seq[(Long, Int)] = store.iterator(cfName).filter { kv =>
          val keyRow = kv.key
          !keyRow.isNullAt(0)
        }.map { kv =>
          val keyRow = kv.key
          val key = (keyRow.getLong(0), keyRow.getInt(1), keyRow.getString(2))
          (key._1, key._2)
        }.toSeq

        val timerTimestampsWithoutNulls = Seq((931L, 10), (452300L, 1),
          (4200L, 68), (90L, 2000), (1L, 27), (1L, 394), (1L, 5), (3L, 980), (35L, 2112),
          (6L, 90118), (9L, 95118), (6L, 87210), (-67450L, 2434), (-803L, 3422))

        assert(result === timerTimestampsWithoutNulls.sorted)

        // verify that the null cols are seen in the correct order filtering for nulls
        val nullRowsWithOrder = store.iterator(cfName).filter { kv =>
          val keyRow = kv.key
          keyRow.isNullAt(0)
        }.map { kv =>
          val keyRow = kv.key
          keyRow.getInt(1)
        }.toSeq

        assert(nullRowsWithOrder === Seq(-5534, -23, 28, 40, 113))
      } finally {
        if (!store.hasCommitted) store.abort()
      }

      val store1 = provider.getStore(0)
      try {
        val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
        if (colFamiliesEnabled) {
          store1.createColFamilyIfAbsent(cfName,
            testSchema, valueSchema,
            RangeKeyScanStateEncoderSpec(testSchema, Seq(0, 1)))
        }

        val timerTimestamps1 = Seq((null, 3), (null, 1), (null, 32),
          (null, 113), (null, 40872), (null, -675456), (null, -924), (null, -666),
          (null, 66))
        timerTimestamps1.foreach { ts =>
          // order by long col first and then by int col
          val keyRow = schemaProj.apply(new GenericInternalRow(Array[Any](ts._1, ts._2,
            UTF8String.fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString))))
          val valueRow = dataToValueRow(1)
          store1.put(keyRow, valueRow, cfName)
          assert(valueRowToData(store1.get(keyRow, cfName)) === 1)
        }

        // verify that ordering for non-null columns on the right in still maintained
        val result1: Seq[Int] = store1.iterator(cfName).map { kv =>
          val keyRow = kv.key
          keyRow.getInt(1)
        }.toSeq

        assert(result1 === timerTimestamps1.map(_._2).sorted)
      } finally {
        if (!store1.hasCommitted) store1.abort()
      }
    }
  }

  testWithColumnFamiliesAndEncodingTypes(
    "rocksdb range scan multiple ordering columns - variable size " +
    s"non-ordering columns with null values in second ordering column",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    val testSchema: StructType = StructType(
      Seq(StructField("key1", LongType, true),
        StructField("key2", IntegerType, true),
        StructField("key3", StringType, false)))

    val schemaProj = UnsafeProjection.create(Array[DataType](LongType, IntegerType, StringType))

    tryWithProviderResource(newStoreProvider(testSchema,
      RangeKeyScanStateEncoderSpec(testSchema, Seq(0, 1)), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          testSchema, valueSchema,
          RangeKeyScanStateEncoderSpec(testSchema, Seq(0, 1)))
      }

      val timerTimestamps = Seq((931L, 10), (40L, null), (452300L, 1),
        (-133L, null), (-344555L, 2424), (-4342499L, null),
        (4200L, 68), (90L, 2000), (1L, 27), (1L, 394), (1L, 5), (3L, 980), (35L, 2112),
        (6L, 90118), (9L, 95118), (6L, 87210), (113L, null), (100L, null))
      timerTimestamps.foreach { ts =>
        // order by long col first and then by int col
        val keyRow = schemaProj.apply(new GenericInternalRow(Array[Any](ts._1, ts._2,
          UTF8String.fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString))))
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store.get(keyRow, cfName)) === 1)
      }

      // verify that the expected null cols are seen
      val nullRows = store.iterator(cfName).filter { kv =>
        val keyRow = kv.key
        keyRow.isNullAt(1)
      }
      assert(nullRows.size === 5)

      // the ordering based on first col which has non-null values should be preserved
      val result: Seq[(Long, Int)] = store.iterator(cfName)
        .map { kv =>
          val keyRow = kv.key
          val key = (keyRow.getLong(0), keyRow.getInt(1), keyRow.getString(2))
          (key._1, key._2)
        }.toSeq
      assert(result.map(_._1) === timerTimestamps.map(_._1).sorted)
    }
  }

  testWithColumnFamiliesAndEncodingTypes(
    "rocksdb range scan byte ordering column - variable size " +
    s"non-ordering columns",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    val testSchema: StructType = StructType(
      Seq(StructField("key1", ByteType, false),
        StructField("key2", IntegerType, false),
        StructField("key3", StringType, false)))

    val schemaProj = UnsafeProjection.create(Array[DataType](ByteType, IntegerType, StringType))

    tryWithProviderResource(newStoreProvider(testSchema,
      RangeKeyScanStateEncoderSpec(testSchema, Seq(0, 1)), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          testSchema, valueSchema,
          RangeKeyScanStateEncoderSpec(testSchema, Seq(0, 1)))
      }

      val timerTimestamps: Seq[(Byte, Int)] = Seq((0x33, 10), (0x1A, 40), (0x1F, 1), (0x01, 68),
        (0x7F, 2000), (0x01, 27), (0x01, 394), (0x01, 5), (0x03, 980), (0x35, 2112),
        (0x11, -190), (0x1A, -69), (0x01, -344245), (0x31, -901),
        (-0x01, 90118), (-0x7F, 95118), (-0x80, 87210))
      timerTimestamps.foreach { ts =>
        // order by byte col first and then by int col
        val keyRow = schemaProj.apply(new GenericInternalRow(Array[Any](ts._1, ts._2,
          UTF8String.fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString))))
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store.get(keyRow, cfName)) === 1)
      }

      val result: Seq[(Byte, Int)] = store.iterator(cfName).map { kv =>
        val keyRow = kv.key
        val key = (keyRow.getByte(0), keyRow.getInt(1), keyRow.getString(2))
        (key._1, key._2)
      }.toSeq
      assert(result === timerTimestamps.sorted)
    }
  }

  testWithColumnFamiliesAndEncodingTypes(
    "rocksdb range scan - ordering cols and key schema cols are same",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    // use the same schema as value schema for single col key schema
    tryWithProviderResource(newStoreProvider(valueSchema,
      RangeKeyScanStateEncoderSpec(valueSchema, Seq(0)), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)
      try {
        val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
        if (colFamiliesEnabled) {
          store.createColFamilyIfAbsent(cfName,
            valueSchema, valueSchema,
            RangeKeyScanStateEncoderSpec(valueSchema, Seq(0)))
        }

        val timerTimestamps = Seq(931, 8000, 452300, 4200,
          -3545, -343, 133, -90, -8014490, -79247,
          90, 1, 2, 8, 3, 35, 6, 9, 5, -233)
        timerTimestamps.foreach { ts =>
          // non-timestamp col is of variable size
          val keyRow = dataToValueRow(ts)
          val valueRow = dataToValueRow(1)
          store.put(keyRow, valueRow, cfName)
          assert(valueRowToData(store.get(keyRow, cfName)) === 1)
        }

        val result = store.iterator(cfName).map { kv =>
          valueRowToData(kv.key)
        }.toSeq
        assert(result === timerTimestamps.sorted)

        // also check for prefix scan
        timerTimestamps.foreach { ts =>
          val prefix = dataToValueRow(ts)
          val result = store.prefixScan(prefix, cfName).map { kv =>
            assert(valueRowToData(kv.value) === 1)
            valueRowToData(kv.key)
          }.toSeq
          assert(result.size === 1)
        }
        store.commit()
      } finally {
        if (!store.hasCommitted) store.abort()
      }
    }
  }

  testWithColumnFamiliesAndEncodingTypes("rocksdb range scan - with prefix scan",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    tryWithProviderResource(newStoreProvider(keySchemaWithRangeScan,
      RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, Seq(0)),
      colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)
      try {
        val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
        if (colFamiliesEnabled) {
          store.createColFamilyIfAbsent(cfName,
            keySchemaWithRangeScan, valueSchema,
            RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, Seq(0)))
        }

        val timerTimestamps = Seq(931L, -1331L, 8000L, 1L, -244L, -8350L, -55L)
        timerTimestamps.zipWithIndex.foreach { case (ts, idx) =>
          (1 to idx + 1).foreach { keyVal =>
            val keyRow = dataToKeyRowWithRangeScan(ts, keyVal.toString)
            val valueRow = dataToValueRow(1)
            store.put(keyRow, valueRow, cfName)
            assert(valueRowToData(store.get(keyRow, cfName)) === 1)
          }
        }

        timerTimestamps.zipWithIndex.foreach { case (ts, idx) =>
          val prefix = dataToPrefixKeyRowWithRangeScan(ts)
          val result = store.prefixScan(prefix, cfName).map { kv =>
            assert(valueRowToData(kv.value) === 1)
            val key = keyRowWithRangeScanToData(kv.key)
            key._2
          }.toSeq
          assert(result.size === idx + 1)
        }
        store.commit()
      } finally {
        if (!store.hasCommitted) store.abort()
      }
    }
  }

  testWithColumnFamiliesAndEncodingTypes(
    "rocksdb key and value schema encoders for column families",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    val testColFamily = "testState"

    tryWithProviderResource(newStoreProvider(colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(testColFamily,
          keySchema, valueSchema, NoPrefixKeyStateEncoderSpec(keySchema))
        val keyRow1 = dataToKeyRow("a", 0)
        val valueRow1 = dataToValueRow(1)
        store.put(keyRow1, valueRow1, colFamilyName = testColFamily)
        assert(valueRowToData(store.get(keyRow1, colFamilyName = testColFamily)) === 1)
        store.remove(keyRow1, colFamilyName = testColFamily)
        assert(store.get(keyRow1, colFamilyName = testColFamily) === null)
      }
      val keyRow2 = dataToKeyRow("b", 0)
      val valueRow2 = dataToValueRow(2)
      store.put(keyRow2, valueRow2)
      assert(valueRowToData(store.get(keyRow2)) === 2)
      store.remove(keyRow2)
      assert(store.get(keyRow2) === null)
    }
  }

  test("validate rocksdb values iterator correctness") {
    withSQLConf(SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.key -> "1") {
      tryWithProviderResource(newStoreProvider(useColumnFamilies = true,
        useMultipleValuesPerKey = true)) { provider =>
        val store = provider.getStore(0)
        // Verify state after updating
        put(store, "a", 0, 1)

        val iterator0 = store.valuesIterator(dataToKeyRow("a", 0))

        assert(iterator0.hasNext)
        assert(valueRowToData(iterator0.next()) === 1)
        assert(!iterator0.hasNext)

        merge(store, "a", 0, 2)
        merge(store, "a", 0, 3)

        val iterator1 = store.valuesIterator(dataToKeyRow("a", 0))

        (1 to 3).map { i =>
          assert(iterator1.hasNext)
          assert(valueRowToData(iterator1.next()) === i)
        }

        assert(!iterator1.hasNext)

        remove(store, _._1 == "a")
        val iterator2 = store.valuesIterator(dataToKeyRow("a", 0))
        assert(!iterator2.hasNext)

        assert(get(store, "a", 0).isEmpty)
      }
    }
  }

  /* Column family related tests */
  testWithColumnFamiliesAndEncodingTypes("column family creation with invalid names",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    tryWithProviderResource(
      newStoreProvider(useColumnFamilies = colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      Seq("default", "", " ", "    ", " default", " default ").foreach { colFamilyName =>
        val ex = intercept[SparkUnsupportedOperationException] {
          store.createColFamilyIfAbsent(colFamilyName,
            keySchema, valueSchema, NoPrefixKeyStateEncoderSpec(keySchema))
        }

        if (!colFamiliesEnabled) {
          checkError(
            ex,
            condition = "STATE_STORE_UNSUPPORTED_OPERATION",
            parameters = Map(
              "operationType" -> "create_col_family",
              "entity" -> "multiple column families is disabled in RocksDBStateStoreProvider"
            ),
            matchPVals = true
          )
        } else {
          checkError(
            ex,
            condition = "STATE_STORE_CANNOT_USE_COLUMN_FAMILY_WITH_INVALID_NAME",
            parameters = Map(
              "operationName" -> "create_col_family",
              "colFamilyName" -> colFamilyName
            ),
            matchPVals = true
          )
        }
      }
    }
  }

  testWithColumnFamiliesAndEncodingTypes(s"column family creation with reserved chars",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    tryWithProviderResource(
      newStoreProvider(useColumnFamilies = colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      Seq("$internal", "$test", "$test123", "$_12345", "$$$235").foreach { colFamilyName =>
        val ex = intercept[SparkUnsupportedOperationException] {
          store.createColFamilyIfAbsent(colFamilyName,
            keySchema, valueSchema, NoPrefixKeyStateEncoderSpec(keySchema))
        }

        if (!colFamiliesEnabled) {
          checkError(
            ex,
            condition = "STATE_STORE_UNSUPPORTED_OPERATION",
            parameters = Map(
              "operationType" -> "create_col_family",
              "entity" -> "multiple column families is disabled in RocksDBStateStoreProvider"
            ),
            matchPVals = true
          )
        } else {
          checkError(
            ex,
            condition = "STATE_STORE_CANNOT_CREATE_COLUMN_FAMILY_WITH_RESERVED_CHARS",
            parameters = Map(
              "colFamilyName" -> colFamilyName
            ),
            matchPVals = false
          )
        }
      }
    }
  }

  testWithColumnFamiliesAndEncodingTypes(s"operations on absent column family",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    tryWithProviderResource(
      newStoreProvider(useColumnFamilies = colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      try {
        val colFamilyName = "test"

        verifyStoreOperationUnsupported("put", colFamiliesEnabled, colFamilyName) {
          store.put(dataToKeyRow("a", 1), dataToValueRow(1), colFamilyName)
        }

        verifyStoreOperationUnsupported("remove", colFamiliesEnabled, colFamilyName) {
          store.remove(dataToKeyRow("a", 1), colFamilyName)
        }

        verifyStoreOperationUnsupported("get", colFamiliesEnabled, colFamilyName) {
          store.get(dataToKeyRow("a", 1), colFamilyName)
        }

        verifyStoreOperationUnsupported("iterator", colFamiliesEnabled, colFamilyName) {
          store.iterator(colFamilyName)
        }

        verifyStoreOperationUnsupported("merge", colFamiliesEnabled, colFamilyName) {
          store.merge(dataToKeyRow("a", 1), dataToValueRow(1), colFamilyName)
        }

        verifyStoreOperationUnsupported("prefixScan", colFamiliesEnabled, colFamilyName) {
          store.prefixScan(dataToKeyRow("a", 1), colFamilyName)
        }
      } finally {
        if (!store.hasCommitted) store.abort()
      }
    }
  }

  test(s"get, put, iterator, commit, load with multiple column families") {
    tryWithProviderResource(newStoreProvider(useColumnFamilies = true)) { provider =>
      def get(store: StateStore, col1: String, col2: Int, colFamilyName: String): UnsafeRow = {
        store.get(dataToKeyRow(col1, col2), colFamilyName)
      }

      def iterator(store: StateStore, colFamilyName: String): Iterator[((String, Int), Int)] = {
        store.iterator(colFamilyName).map {
          case unsafePair =>
            (keyRowToData(unsafePair.key), valueRowToData(unsafePair.value))
        }
      }

      def put(store: StateStore, key: (String, Int), value: Int, colFamilyName: String): Unit = {
        store.put(dataToKeyRow(key._1, key._2), dataToValueRow(value), colFamilyName)
      }

      var store = provider.getStore(0)

      val colFamily1: String = "abc"
      val colFamily2: String = "xyz"
      store.createColFamilyIfAbsent(colFamily1, keySchema, valueSchema,
        NoPrefixKeyStateEncoderSpec(keySchema))
      store.createColFamilyIfAbsent(colFamily2, keySchema, valueSchema,
        NoPrefixKeyStateEncoderSpec(keySchema))

      assert(get(store, "a", 1, colFamily1) === null)
      assert(iterator(store, colFamily1).isEmpty)
      put(store, ("a", 1), 1, colFamily1)
      assert(valueRowToData(get(store, "a", 1, colFamily1)) === 1)

      assert(get(store, "a", 1, colFamily2) === null)
      assert(iterator(store, colFamily2).isEmpty)
      put(store, ("a", 1), 1, colFamily2)
      assert(valueRowToData(get(store, "a", 1, colFamily2)) === 1)

      store.commit()

      // reload version 0
      store = provider.getStore(0)
      val e = intercept[Exception]{
        get(store, "a", 1, colFamily1)
      }
      checkError(
        exception = e.asInstanceOf[StateStoreUnsupportedOperationOnMissingColumnFamily],
        condition = "STATE_STORE_UNSUPPORTED_OPERATION_ON_MISSING_COLUMN_FAMILY",
        sqlState = Some("42802"),
        parameters = Map("operationType" -> "get", "colFamilyName" -> colFamily1)
      )
      store.abort()

      store = provider.getStore(1)
      // version 1 data recovered correctly
      assert(valueRowToData(get(store, "a", 1, colFamily1)) == 1)
      assert(iterator(store, colFamily1).toSet === Set((("a", 1), 1)))
      // make changes but do not commit version 2
      put(store, ("b", 1), 2, colFamily1)
      assert(valueRowToData(get(store, "b", 1, colFamily1)) === 2)
      assert(iterator(store, colFamily1).toSet === Set((("a", 1), 1), (("b", 1), 2)))
      // version 1 data recovered correctly
      assert(valueRowToData(get(store, "a", 1, colFamily2))== 1)
      assert(iterator(store, colFamily2).toSet === Set((("a", 1), 1)))
      // make changes but do not commit version 2
      put(store, ("b", 1), 2, colFamily2)
      assert(valueRowToData(get(store, "b", 1, colFamily2))=== 2)
      assert(iterator(store, colFamily2).toSet === Set((("a", 1), 1), (("b", 1), 2)))

      store.commit()
    }
  }


  test("verify that column family id is assigned correctly after removal") {
    tryWithProviderResource(newStoreProvider(useColumnFamilies = true)) { provider =>
      var store = getRocksDBStateStore(provider, 0)
      val colFamily1: String = "abc"
      val colFamily2: String = "def"
      val colFamily3: String = "ghi"
      val colFamily4: String = "jkl"
      val colFamily5: String = "mno"

      store.createColFamilyIfAbsent(colFamily1, keySchema, valueSchema,
        NoPrefixKeyStateEncoderSpec(keySchema))
      store.createColFamilyIfAbsent(colFamily2, keySchema, valueSchema,
        NoPrefixKeyStateEncoderSpec(keySchema))
      store.commit()

      store = getRocksDBStateStore(provider, 1)
      store.removeColFamilyIfExists(colFamily2)
      store.commit()

      store = getRocksDBStateStore(provider, 2)
      store.createColFamilyIfAbsent(colFamily3, keySchema, valueSchema,
        NoPrefixKeyStateEncoderSpec(keySchema))
      store.removeColFamilyIfExists(colFamily1)
      store.removeColFamilyIfExists(colFamily3)
      store.commit()

      store = getRocksDBStateStore(provider, 1)
      // this should return the old id, because we didn't remove this colFamily for version 1
      store.createColFamilyIfAbsent(colFamily1, keySchema, valueSchema,
        NoPrefixKeyStateEncoderSpec(keySchema))
      store.abort()

      store = getRocksDBStateStore(provider, 3)
      store.createColFamilyIfAbsent(colFamily4, keySchema, valueSchema,
        NoPrefixKeyStateEncoderSpec(keySchema))
      store.createColFamilyIfAbsent(colFamily5, keySchema, valueSchema,
        NoPrefixKeyStateEncoderSpec(keySchema))
      store.abort()
    }
  }

  Seq(
    NoPrefixKeyStateEncoderSpec(keySchema), PrefixKeyScanStateEncoderSpec(keySchema, 1)
  ).foreach { keyEncoder =>
    testWithColumnFamiliesAndEncodingTypes(s"validate rocksdb " +
      s"${keyEncoder.getClass.toString.split('.').last} correctness",
      TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
        tryWithProviderResource(newStoreProvider(keySchema, keyEncoder,
          colFamiliesEnabled)) { provider =>
          val store = provider.getStore(0)

          val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
          if (colFamiliesEnabled) {
            store.createColFamilyIfAbsent(cfName, keySchema, valueSchema, keyEncoder)
          }

          var timerTimestamps = Seq(931L, 8000L, 452300L, 4200L, -1L, 90L, 1L, 2L, 8L,
            -230L, -14569L, -92L, -7434253L, 35L, 6L, 9L, -323L, 5L)
          // put & get, iterator
          timerTimestamps.foreach { ts =>
            val keyRow = if (ts < 0) {
              dataToKeyRow("a", ts.toInt)
            } else dataToKeyRow(ts.toString, ts.toInt)
            val valueRow = dataToValueRow(1)
            store.put(keyRow, valueRow, cfName)
            assert(valueRowToData(store.get(keyRow, cfName)) === 1)
          }
          assert(store.iterator(cfName).toSeq.length == timerTimestamps.length)

          // remove
          store.remove(dataToKeyRow(1L.toString, 1.toInt), cfName)
          timerTimestamps = timerTimestamps.filter(_ != 1L)
          assert(store.iterator(cfName).toSeq.length == timerTimestamps.length)

          // prefix scan
          if (!keyEncoder.isInstanceOf[NoPrefixKeyStateEncoderSpec]) {
            val keyRow = dataToPrefixKeyRow("a")
            assert(store.prefixScan(keyRow, cfName).toSeq.length
              == timerTimestamps.filter(_ < 0).length)
          }

          store.commit()
        }
    }
  }

  testWithColumnFamiliesAndEncodingTypes(s"numInternalKeys metrics",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    tryWithProviderResource(
      newStoreProvider(useColumnFamilies = colFamiliesEnabled)) { provider =>
      if (colFamiliesEnabled) {
        val store = provider.getStore(0)

        // create non-internal col family and add data
        val cfName = "testColFamily"
        store.createColFamilyIfAbsent(cfName, keySchema, valueSchema,
          NoPrefixKeyStateEncoderSpec(keySchema))
        put(store, "a", 0, 1, cfName)
        put(store, "b", 0, 2, cfName)
        put(store, "c", 0, 3, cfName)
        put(store, "d", 0, 4, cfName)
        put(store, "e", 0, 5, cfName)

        // create internal col family and add data
        val internalCfName = "$testIndex"
        store.createColFamilyIfAbsent(internalCfName, keySchema, valueSchema,
          NoPrefixKeyStateEncoderSpec(keySchema), isInternal = true)
        put(store, "a", 0, 1, internalCfName)
        put(store, "m", 0, 2, internalCfName)
        put(store, "n", 0, 3, internalCfName)
        put(store, "b", 0, 4, internalCfName)

        assert(store.commit() === 1)
        // Commit and verify that the metrics are correct for internal and non-internal col families
        assert(store.metrics.numKeys === 5)
        val metricPair = store
          .metrics.customMetrics.find(_._1.name == "rocksdbNumInternalColFamiliesKeys")
        assert(metricPair.isDefined && metricPair.get._2 === 4)
        val store1 = provider.getStore(1)
        assert(rowPairsToDataSet(store1.iterator(cfName)) ===
          Set(("a", 0) -> 1, ("b", 0) -> 2, ("c", 0) -> 3, ("d", 0) -> 4, ("e", 0) -> 5))
        assert(rowPairsToDataSet(store1.iterator(internalCfName)) ===
          Set(("a", 0) -> 1, ("m", 0) -> 2, ("n", 0) -> 3, ("b", 0) -> 4))
        store1.abort()

        // Reload the store and remove some keys
        val reloadedProvider = newStoreProvider(store.id, colFamiliesEnabled)
        val reloadedStore = reloadedProvider.getStore(1)
        reloadedStore.createColFamilyIfAbsent(cfName, keySchema, valueSchema,
          NoPrefixKeyStateEncoderSpec(keySchema))
        reloadedStore.createColFamilyIfAbsent(internalCfName, keySchema, valueSchema,
          NoPrefixKeyStateEncoderSpec(keySchema), isInternal = true)
        remove(reloadedStore, _._1 == "b", cfName)
        remove(reloadedStore, _._1 == "m", internalCfName)
        assert(reloadedStore.commit() === 2)
        // Commit and verify that the metrics are correct for internal and non-internal col families
        assert(reloadedStore.metrics.numKeys === 4)
        val metricPairUpdated = reloadedStore
          .metrics.customMetrics.find(_._1.name == "rocksdbNumInternalColFamiliesKeys")
        assert(metricPairUpdated.isDefined && metricPairUpdated.get._2 === 3)
        val reloadedStore1 = reloadedProvider.getStore(2)
        assert(rowPairsToDataSet(reloadedStore1.iterator(cfName)) ===
          Set(("a", 0) -> 1, ("c", 0) -> 3, ("d", 0) -> 4, ("e", 0) -> 5))
        assert(rowPairsToDataSet(reloadedStore1.iterator(internalCfName)) ===
          Set(("a", 0) -> 1, ("n", 0) -> 3, ("b", 0) -> 4))
        reloadedStore1.commit()
      }
    }
  }

  test(s"validate rocksdb removeColFamilyIfExists correctness") {
    Seq(
      NoPrefixKeyStateEncoderSpec(keySchema),
      PrefixKeyScanStateEncoderSpec(keySchema, 1),
      RangeKeyScanStateEncoderSpec(keySchema, Seq(1))
    ).foreach { keyEncoder =>
      tryWithProviderResource(newStoreProvider(keySchema, keyEncoder, true)) { provider =>
        val store = provider.getStore(0)

        try {
          val cfName = "testColFamily"
          store.createColFamilyIfAbsent(cfName, keySchema, valueSchema, keyEncoder)

          // remove non-exist col family will return false
          assert(!store.removeColFamilyIfExists("non-existence"))

          // put some test data into state store
          val timerTimestamps = Seq(931L, 8000L, 452300L, 4200L, -1L, 90L, 1L, 2L, 8L,
            -230L, -14569L, -92L, -7434253L, 35L, 6L, 9L, -323L, 5L)
          timerTimestamps.foreach { ts =>
            val keyRow = dataToKeyRow(ts.toString, ts.toInt)
            val valueRow = dataToValueRow(1)
            store.put(keyRow, valueRow, cfName)
          }
          assert(store.iterator(cfName).toSeq.length == timerTimestamps.length)

          // assert col family existence
          assert(store.removeColFamilyIfExists(cfName))

          val e = intercept[Exception] {
            store.iterator(cfName)
          }

          checkError(
            exception = e.asInstanceOf[StateStoreUnsupportedOperationOnMissingColumnFamily],
            condition = "STATE_STORE_UNSUPPORTED_OPERATION_ON_MISSING_COLUMN_FAMILY",
            sqlState = Some("42802"),
            parameters = Map("operationType" -> "iterator", "colFamilyName" -> cfName)
          )
        } finally {
          store.abort()
        }
      }
    }
  }

  test("state transitions with commit and illegal operations") {
    tryWithProviderResource(newStoreProvider(useColumnFamilies = false)) { provider =>
      // Get a store and put some data
      val store = provider.getStore(0)
      put(store, "a", 0, 1)
      put(store, "b", 0, 2)

      // Verify data is accessible before commit
      assert(get(store, "a", 0) === Some(1))
      assert(get(store, "b", 0) === Some(2))

      // Commit the changes
      assert(store.commit() === 1)
      assert(store.hasCommitted)

      // Operations after commit should fail with IllegalStateException
      val exception = intercept[StateStoreInvalidStamp] {
        put(store, "c", 0, 3)
      }
      assert(exception.getMessage.contains("Invalid stamp"))

      // Getting a new store for the same version should work
      val store1 = provider.getStore(1)
      assert(get(store1, "a", 0) === Some(1))
      assert(get(store1, "b", 0) === Some(2))

      // Can update the new store instance
      put(store1, "c", 0, 3)
      assert(get(store1, "c", 0) === Some(3))

      // Commit the new changes
      assert(store1.commit() === 2)
    }
  }

  test("state transitions with abort and subsequent operations") {
    tryWithProviderResource(newStoreProvider(useColumnFamilies = false)) { provider =>
      // Get a store and put some data
      val store = provider.getStore(0)
      put(store, "a", 0, 1)
      put(store, "b", 0, 2)

      // Abort the changes
      store.abort()

      // Operations after abort should fail with IllegalStateException
      val exception = intercept[StateStoreInvalidStamp] {
        put(store, "c", 0, 3)
      }
      assert(exception.getMessage.contains("Invalid stamp"))

      // Get a new store, should be empty since previous changes were aborted
      val store1 = provider.getStore(0)
      assert(store1.iterator().isEmpty)

      // Put data and commit
      put(store1, "d", 0, 4)
      assert(store1.commit() === 1)

      // Get a new store and verify data
      val store2 = provider.getStore(1)
      assert(get(store2, "d", 0) === Some(4))
      store2.commit()
    }
  }

  test("abort after commit throws StateStoreOperationOutOfOrder") {
    tryWithProviderResource(newStoreProvider(useColumnFamilies = false)) { provider =>
      val store = provider.getStore(0)
      put(store, "a", 0, 1)
      assert(store.commit() === 1)

      // Abort after commit should throw a SparkRuntimeException
      val exception = intercept[SparkRuntimeException] {
        store.abort()
      }
      checkError(
        exception,
        condition = "STATE_STORE_OPERATION_OUT_OF_ORDER",
        parameters = Map("errorMsg" ->
          ("Expected possible states " +
          "(UPDATING, ABORTED) but found COMMITTED"))
      )

      // Get a new store and verify data was committed
      val store1 = provider.getStore(1)
      assert(get(store1, "a", 0) === Some(1))
      store1.commit()
    }
  }

  test("multiple aborts are idempotent") {
    tryWithProviderResource(newStoreProvider(useColumnFamilies = false)) { provider =>
      val store = provider.getStore(0)
      put(store, "a", 0, 1)

      // First abort
      store.abort()

      // Second abort should not throw
      store.abort()

      // Operations should still fail
      val exception = intercept[StateStoreInvalidStamp] {
        put(store, "b", 0, 2)
      }
      assert(exception.getMessage.contains("Invalid stamp"))
    }
  }

  test("multiple commits throw exception") {
    tryWithProviderResource(newStoreProvider(useColumnFamilies = false)) { provider =>
      val store = provider.getStore(0)
      put(store, "a", 0, 1)
      assert(store.commit() === 1)

      // Second commit should fail with stamp verification
      val exception = intercept[SparkRuntimeException] {
        store.commit()
      }
      checkError(
        exception,
        condition = "STATE_STORE_OPERATION_OUT_OF_ORDER",
        parameters = Map("errorMsg" ->
          "Expected possible states (UPDATING) but found COMMITTED")
      )
    }
  }

  test("get metrics works only after commit") {
    tryWithProviderResource(newStoreProvider(useColumnFamilies = false)) { provider =>
      val store = provider.getStore(0)
      put(store, "a", 0, 1)

      // Getting metrics before commit should throw
      val exception = intercept[SparkRuntimeException] {
        store.metrics
      }
      checkError(
        exception,
        condition = "STATE_STORE_OPERATION_OUT_OF_ORDER",
        parameters = Map("errorMsg" -> "Cannot get metrics in UPDATING state")
      )
      // Commit the changes
      assert(store.commit() === 1)

      // Getting metrics after commit should work
      val metrics = store.metrics
      assert(metrics.numKeys === 1)
    }
  }

  test("get checkpoint info works only after commit") {
    tryWithProviderResource(newStoreProvider(useColumnFamilies = false)) { provider =>
      val store = provider.getStore(0)
      put(store, "a", 0, 1)

      // Getting checkpoint info before commit should throw
      val exception = intercept[SparkRuntimeException] {
        store.getStateStoreCheckpointInfo()
      }
      checkError(
        exception,
        condition = "STATE_STORE_OPERATION_OUT_OF_ORDER",
        parameters = Map("errorMsg" -> "Cannot get metrics in UPDATING state")
      )

      // Commit the changes
      assert(store.commit() === 1)

      // Getting checkpoint info after commit should work
      val checkpointInfo = store.getStateStoreCheckpointInfo()
      assert(checkpointInfo != null)
    }
  }

  test("read store and write store with common stamp") {
    tryWithProviderResource(newStoreProvider(useColumnFamilies = false)) { provider =>
      // First prepare some data
      val initialStore = provider.getStore(0)
      put(initialStore, "a", 0, 1)
      assert(initialStore.commit() === 1)

      // Get a read store
      val readStore = provider.getReadStore(1)
      assert(get(readStore, "a", 0) === Some(1))

      // Get a write store from the read store
      val writeStore = provider.upgradeReadStoreToWriteStore(
        readStore, 1)

      // Verify data access
      assert(get(writeStore, "a", 0) === Some(1))

      // Update through write store
      put(writeStore, "b", 0, 2)
      assert(get(writeStore, "b", 0) === Some(2))

      // Commit the write store
      assert(writeStore.commit() === 2)

      // Get a new store and verify
      val newStore = provider.getStore(2)
      assert(get(newStore, "a", 0) === Some(1))
      assert(get(newStore, "b", 0) === Some(2))
      newStore.commit()
    }
  }

  test("verify operation validation before and after commit") {
    tryWithProviderResource(newStoreProvider(useColumnFamilies = false)) { provider =>
      val store = provider.getStore(0)

      // Put operations should work in UPDATING state
      put(store, "a", 0, 1)
      assert(get(store, "a", 0) === Some(1))

      // Remove operations should work in UPDATING state
      remove(store, _._1 == "a")
      assert(get(store, "a", 0) === None)

      // Iterator operations should work in UPDATING state
      put(store, "b", 0, 2)
      assert(rowPairsToDataSet(store.iterator()) === Set(("b", 0) -> 2))

      // Commit should work in UPDATING state
      assert(store.commit() === 1)

      intercept[StateStoreInvalidStamp] {
        put(store, "c", 0, 3)
      }

      intercept[StateStoreInvalidStamp] {
        remove(store, _._1 == "b")
      }

      intercept[StateStoreInvalidStamp] {
        store.iterator()
      }

      // Get a new store for the next version
      val store1 = provider.getStore(1)

      // Abort the store
      store1.abort()

      // Operations after abort should fail due to invalid stamp
      intercept[StateStoreInvalidStamp] {
        put(store1, "c", 0, 3)
      }
    }
  }

  test("Rocks DB task completion listener does not double unlock acquireThread") {
    // This test verifies that a thread that locks then unlocks the db and then
    // fires a completion listener (Thread 1) does not unlock the lock validly
    // acquired by another thread (Thread 2).
    //
    // Timeline of this test (* means thread is active):
    // STATE | MAIN             | THREAD 1         | THREAD 2         |
    // ------| ---------------- | ---------------- | ---------------- |
    // 0.    | wait for s3      | *load, commit    | wait for s1      |
    //       |                  | *signal s1       |                  |
    // ------| ---------------- | ---------------- | ---------------- |
    // 1.    |                  | wait for s2      | *load, signal s2 |
    // ------| ---------------- | ---------------- | ---------------- |
    // 2.    |                  | *task complete   | wait for s4      |
    //       |                  | *signal s3, END  |                  |
    // ------| ---------------- | ---------------- | ---------------- |
    // 3.    | *verify locked   |                  |                  |
    //       | *signal s4       |                  |                  |
    // ------| ---------------- | ---------------- | ---------------- |
    // 4.    | wait for s5      |                  | *commit          |
    //       |                  |                  | *signal s5, END  |
    // ------| ---------------- | ---------------- | ---------------- |
    // 5.    | *close db, END   |                  |                  |
    //
    // NOTE: state 4 and 5 are only for cleanup

    // Create a custom ExecutionContext with 3 threads
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(
      ThreadUtils.newDaemonFixedThreadPool(3, "pool-thread-executor"))
    val stateLock = new Object()
    var state = 0

    tryWithProviderResource(newStoreProvider()) { provider =>
      Future { // THREAD 1
        // Set thread 1's task context so that it is not a clone
        // of the main thread's taskContext, which will end if the
        // task is marked as complete
        val taskContext = TaskContext.empty()
        TaskContext.setTaskContext(taskContext)

        stateLock.synchronized {
          // -------------------- STATE 0 --------------------
          // Simulate a task that loads and commits, db should be unlocked after
          val store = provider.getStore(0)
          store.commit()
          // Signal that we have entered state 1
          state = 1
          stateLock.notifyAll()

          // -------------------- STATE 2 --------------------
          // Wait until we have entered state 2 (thread 2 has loaded db and acquired lock)
          while (state != 2) {
            stateLock.wait()
          }

          // thread 1's task context is marked as complete and signal
          // that we have entered state 3
          // At this point, thread 2 should still hold the DB lock.
          taskContext.markTaskCompleted(None)
          state = 3
          stateLock.notifyAll()
        }
      }

      Future { // THREAD 2
        // Set thread 2's task context so that it is not a clone of thread 1's
        // so it won't be marked as complete
        val taskContext = TaskContext.empty()
        TaskContext.setTaskContext(taskContext)

        stateLock.synchronized {
          // -------------------- STATE 1 --------------------
          // Wait until we have entered state 1 (thread 1 finished loading and committing)
          while (state != 1) {
            stateLock.wait()
          }

          // Load the db and signal that we have entered state 2
          val store = provider.getStore(1)
          assertAcquiredThreadIsCurrentThread(provider)
          state = 2
          stateLock.notifyAll()

          // -------------------- STATE 4 --------------------
          // Wait until we have entered state 4 (thread 1 completed and
          // main thread confirmed that lock is held)
          while (state != 4) {
            stateLock.wait()
          }

          // Ensure we still have the lock
          assertAcquiredThreadIsCurrentThread(provider)

          // commit and signal that we have entered state 5
          store.commit()
          state = 5
          stateLock.notifyAll()
        }
      }

      // MAIN THREAD
      stateLock.synchronized {
        // -------------------- STATE 3 --------------------
        // Wait until we have entered state 3 (thread 1 is complete)
        while (state != 3) {
          stateLock.wait()
        }

        // Verify that the lock is being held
        val stateMachine = PrivateMethod[Any](Symbol("stateMachine"))
        val stateMachineObj = provider invokePrivate stateMachine()
        val threadInfo = stateMachineObj.asInstanceOf[RocksDBStateMachine].getAcquiredThreadInfo
        assert(threadInfo.nonEmpty, s"acquiredThreadInfo was None when it should be Some")

        // Signal that we have entered state 4 (thread 2 can now release lock)
        state = 4
        stateLock.notifyAll()

        // -------------------- STATE 5 --------------------
        // Wait until we have entered state 5 (thread 2 has released lock)
        // so that we can clean up
        while (state != 5) {
          stateLock.wait()
        }
      }
    }
  }

  test("RocksDB task completion listener correctly releases for failed task") {
    // This test verifies that a thread that locks the DB and then fails
    // can rely on the completion listener to release the lock.

    // Create a custom ExecutionContext with 1 thread
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(
      ThreadUtils.newDaemonSingleThreadExecutor("single-thread-executor"))
    val timeout = 5.seconds

    tryWithProviderResource(newStoreProvider()) { provider =>
      // New task that will load and then complete with failure
      val fut = Future {
        val taskContext = TaskContext.empty()
        TaskContext.setTaskContext(taskContext)

        provider.getStore(0)
        assertAcquiredThreadIsCurrentThread(provider)

        // Task completion listener should unlock
        taskContext.markTaskCompleted(
          Some(new SparkException("Task failure injection")))
      }

      ThreadUtils.awaitResult(fut, timeout)

      // Assert that db is not locked
      val stateMachine = PrivateMethod[Any](Symbol("stateMachine"))
      val stateMachineObj = provider invokePrivate stateMachine()
      val stamp = stateMachineObj.asInstanceOf[RocksDBStateMachine].currentValidStamp.get()
      assert(stamp == -1,
        s"state machine stamp should be -1 (unlocked) but was $stamp")
    }
  }

  private def verifyStoreOperationUnsupported(
      operationName: String,
      colFamiliesEnabled: Boolean,
      colFamilyName: String)
    (testFn: => Unit): Unit = {
    val ex = intercept[SparkUnsupportedOperationException] {
      testFn
    }

    if (!colFamiliesEnabled) {
      checkError(
        ex,
        condition = "STATE_STORE_UNSUPPORTED_OPERATION",
        parameters = Map(
          "operationType" -> operationName,
          "entity" -> "multiple column families is disabled in RocksDBStateStoreProvider"
        ),
        matchPVals = true
      )
    } else {
      checkError(
        ex,
        condition = "STATE_STORE_UNSUPPORTED_OPERATION_ON_MISSING_COLUMN_FAMILY",
        parameters = Map(
          "operationType" -> operationName,
          "colFamilyName" -> colFamilyName
        ),
        matchPVals = true
      )
    }
  }

  override def newStoreProvider(): RocksDBStateStoreProvider = {
    newStoreProvider(StateStoreId(newDir(), Random.nextInt(), 0))
  }

  def newStoreProvider(storeId: StateStoreId): RocksDBStateStoreProvider = {
    newStoreProvider(storeId, NoPrefixKeyStateEncoderSpec(keySchema))
  }

  override def newStoreProvider(storeId: StateStoreId, useColumnFamilies: Boolean):
    RocksDBStateStoreProvider = {
    newStoreProvider(storeId, NoPrefixKeyStateEncoderSpec(keySchema),
    useColumnFamilies = useColumnFamilies)
  }

  override def newStoreProvider(useColumnFamilies: Boolean): RocksDBStateStoreProvider = {
    newStoreProvider(StateStoreId(newDir(), Random.nextInt(), 0),
      NoPrefixKeyStateEncoderSpec(keySchema),
      useColumnFamilies = useColumnFamilies)
  }

  def newStoreProvider(useColumnFamilies: Boolean,
      useMultipleValuesPerKey: Boolean): RocksDBStateStoreProvider = {
    newStoreProvider(StateStoreId(newDir(), Random.nextInt(), 0),
      NoPrefixKeyStateEncoderSpec(keySchema),
      useColumnFamilies = useColumnFamilies,
      useMultipleValuesPerKey = useMultipleValuesPerKey
    )
  }

  def newStoreProvider(storeId: StateStoreId, conf: Configuration): RocksDBStateStoreProvider = {
    newStoreProvider(storeId, NoPrefixKeyStateEncoderSpec(keySchema), conf = conf)
  }

  override def newStoreProvider(
      keySchema: StructType,
      keyStateEncoderSpec: KeyStateEncoderSpec,
      useColumnFamilies: Boolean): RocksDBStateStoreProvider = {
    newStoreProvider(StateStoreId(newDir(), Random.nextInt(), 0),
      keyStateEncoderSpec = keyStateEncoderSpec,
      keySchema = keySchema,
      useColumnFamilies = useColumnFamilies)
  }

  def newStoreProvider(
      storeId: StateStoreId,
      keyStateEncoderSpec: KeyStateEncoderSpec,
      keySchema: StructType = keySchema,
      sqlConf: Option[SQLConf] = None,
      conf: Configuration = new Configuration,
      useColumnFamilies: Boolean = false,
      useMultipleValuesPerKey: Boolean = false): RocksDBStateStoreProvider = {
    val provider = new RocksDBStateStoreProvider()
    val testStateSchemaProvider = new TestStateSchemaProvider
    conf.set(StreamExecution.RUN_ID_KEY, UUID.randomUUID().toString)
    provider.init(
      storeId,
      keySchema,
      valueSchema,
      keyStateEncoderSpec,
      useColumnFamilies,
      new StateStoreConf(sqlConf.getOrElse(SQLConf.get)),
      conf,
      useMultipleValuesPerKey,
      stateSchemaProvider = Some(testStateSchemaProvider))
    provider
  }

  override def getLatestData(
      storeProvider: RocksDBStateStoreProvider,
      useColumnFamilies: Boolean = false): Set[((String, Int), Int)] = {
    getData(storeProvider, version = -1, useColumnFamilies)
  }

  override def getData(
      provider: RocksDBStateStoreProvider,
      version: Int = -1,
      useColumnFamilies: Boolean = false): Set[((String, Int), Int)] = {
    tryWithProviderResource(newStoreProvider(provider.stateStoreId,
      useColumnFamilies)) { reloadedProvider =>
      val versionToRead = if (version < 0) reloadedProvider.latestVersion else version
      reloadedProvider.getStore(versionToRead).iterator().map(rowPairToDataPair).toSet
    }
  }

  override def newStoreProvider(
    minDeltasForSnapshot: Int,
    numOfVersToRetainInMemory: Int): RocksDBStateStoreProvider = {
    newStoreProvider(StateStoreId(newDir(), Random.nextInt(), 0),
      NoPrefixKeyStateEncoderSpec(keySchema),
      sqlConf = Some(getDefaultSQLConf(minDeltasForSnapshot, numOfVersToRetainInMemory)))
  }

  override def getDefaultSQLConf(
    minDeltasForSnapshot: Int,
    numOfVersToRetainInMemory: Int): SQLConf = {
    val sqlConf = SQLConf.get.clone()
    sqlConf.setConfString(
      SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.key, minDeltasForSnapshot.toString)
    sqlConf
  }

  override def testQuietly(name: String)(f: => Unit): Unit = {
    test(name) {
      quietly {
        f
      }
    }
  }

  override def testWithAllCodec(name: String)(func: Boolean => Any): Unit = {
    Seq(true, false).foreach { colFamiliesEnabled =>
      codecsInShortName.foreach { codecName =>
        super.test(s"$name - with codec $codecName - colFamiliesEnabled=$colFamiliesEnabled") {
          withSQLConf(SQLConf.STATE_STORE_COMPRESSION_CODEC.key -> codecName) {
            func(colFamiliesEnabled)
          }
        }
      }

      CompressionCodec.ALL_COMPRESSION_CODECS.foreach { codecName =>
        super.test(s"$name - with codec $codecName - colFamiliesEnabled=$colFamiliesEnabled") {
          withSQLConf(SQLConf.STATE_STORE_COMPRESSION_CODEC.key -> codecName) {
            func(colFamiliesEnabled)
          }
        }
      }
    }
  }

  def assertAcquiredThreadIsCurrentThread(provider: RocksDBStateStoreProvider): Unit = {
    val stateMachine = PrivateMethod[Any](Symbol("stateMachine"))
    val stateMachineObj = provider invokePrivate stateMachine()
    val threadInfo = stateMachineObj.asInstanceOf[RocksDBStateMachine].getAcquiredThreadInfo
    assert(threadInfo.isDefined,
      "acquired thread info should not be null after load")
    val threadId = threadInfo.get.threadRef.get.get.getId
    assert(
      threadId == Thread.currentThread().getId,
      s"acquired thread should be curent thread ${Thread.currentThread().getId} " +
        s"after load but was $threadId")
  }
}

