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

package org.apache.spark.shuffle.sort;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import javax.annotation.Nullable;

import scala.None$;
import scala.Option;
import scala.Product2;
import scala.Tuple2;
import scala.collection.Iterator;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Closeables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.Partitioner;
import org.apache.spark.ShuffleDependency;
import org.apache.spark.SparkConf;
import org.apache.spark.TaskContext;
import org.apache.spark.executor.ShuffleWriteMetrics;
import org.apache.spark.executor.TaskMetrics;
import org.apache.spark.scheduler.MapStatus;
import org.apache.spark.scheduler.MapStatus$;
import org.apache.spark.serializer.Serializer;
import org.apache.spark.serializer.SerializerInstance;
import org.apache.spark.shuffle.IndexShuffleBlockResolver;
import org.apache.spark.shuffle.ShuffleWriter;
import org.apache.spark.storage.*;
import org.apache.spark.util.Utils;

final class OpsShuffleWriter<K, V> extends ShuffleWriter<K, V> {

  private static final Logger logger = LoggerFactory.getLogger(OpsShuffleWriter.class);

  private final int fileBufferSize;
  private final boolean transferToEnabled;
  private final int numPartitions;
  private final BlockManager blockManager;
  private final Partitioner partitioner;
  private final ShuffleWriteMetrics writeMetrics;
  private final int shuffleId;
  private final int mapId;
  private final Serializer serializer;
  private final IndexShuffleBlockResolver shuffleBlockResolver;

  /** Array of file writers, one for each partition */
  private DiskBlockObjectWriter partitionWriter;
  // private FileSegment[] partitionWriterSegments;
  @Nullable private MapStatus mapStatus;
  private long[] partitionLengths;

  // OPS log
  private TaskMetrics taskMetrics;

  /**
   * Are we in the process of stopping? Because map tasks can call stop() with success = true
   * and then call stop() with success = false if they get an exception, we want to make sure
   * we don't try deleting files, etc twice.
   */
  private boolean stopping = false;

  OpsShuffleWriter(
      BlockManager blockManager,
      IndexShuffleBlockResolver shuffleBlockResolver,
      BypassMergeSortShuffleHandle<K, V> handle,
      int mapId,
      TaskContext taskContext,
      SparkConf conf) {
    // Use getSizeAsKb (not bytes) to maintain backwards compatibility if no units are provided
    this.fileBufferSize = (int) conf.getSizeAsKb("spark.shuffle.file.buffer", "32k") * 1024;
    this.transferToEnabled = conf.getBoolean("spark.file.transferTo", true);
    this.blockManager = blockManager;
    final ShuffleDependency<K, V, V> dep = handle.dependency();
    this.mapId = mapId;
    this.shuffleId = dep.shuffleId();
    this.partitioner = dep.partitioner();
    this.numPartitions = partitioner.numPartitions();
    this.writeMetrics = taskContext.taskMetrics().shuffleWriteMetrics();
    this.taskMetrics = taskContext.taskMetrics();
    this.serializer = dep.serializer();
    this.shuffleBlockResolver = shuffleBlockResolver;
  }

  @Override
  public void write(Iterator<Product2<K, V>> records) throws IOException {
    assert (partitionWriter == null);
    // OPS log
    Long start = System.currentTimeMillis();
    this.taskMetrics.setOpsSortStart(start);

    if (!records.hasNext()) {
      partitionLengths = new long[1];
      shuffleBlockResolver.writeIndexFileAndCommit(shuffleId, mapId, partitionLengths, null);
      mapStatus = MapStatus$.MODULE$.apply(blockManager.shuffleServerId(), partitionLengths);
      return;
    }
    final SerializerInstance serInstance = serializer.newInstance();
    final Tuple2<TempShuffleBlockId, File> tempShuffleBlockIdPlusFile =
      blockManager.diskBlockManager().createTempShuffleBlock();
    // final File file = tempShuffleBlockIdPlusFile._2();
    final BlockId blockId = tempShuffleBlockIdPlusFile._1();
    File output = shuffleBlockResolver.getDataFile(shuffleId, mapId);
    File tmp = Utils.tempFileWith(output);
    partitionWriter = blockManager.getDiskWriter(blockId, tmp, serInstance, fileBufferSize, writeMetrics);

    while (records.hasNext()) {
      final Product2<K, V> record = records.next();
      // final K key = record._1();
      partitionWriter.write(record._1(), record._2());
    }
    partitionWriter.close();
    try {
      partitionLengths = new long[1];
      partitionLengths[0] = tmp.length();
      shuffleBlockResolver.writeIndexFileAndCommit(shuffleId, mapId, partitionLengths, tmp);
    } finally {
      if (tmp.exists() && !tmp.delete()) {
        logger.error("Error while deleting temp file {}", tmp.getAbsolutePath());
      }
    }
    mapStatus = MapStatus$.MODULE$.apply(blockManager.shuffleServerId(), partitionLengths);

    // OPS log
    this.taskMetrics.incOpsSpillTime(System.currentTimeMillis() - start);
  }

  @VisibleForTesting
  long[] getPartitionLengths() {
    return partitionLengths;
  }

  @Override
  public Option<MapStatus> stop(boolean success) {
    if (stopping) {
      return None$.empty();
    } else {
      stopping = true;
      if (success) {
        if (mapStatus == null) {
          throw new IllegalStateException("Cannot call stop(true) without having called write()");
        }
        // final long[] lengths = new long[numPartitions];
        // mapStatus = MapStatus$.MODULE$.apply(blockManager.shuffleServerId(), lengths);
        return Option.apply(mapStatus);
      } else {
        // The map task failed, so delete our output data.
        if (partitionWriter != null) {
          try {
            // for (DiskBlockObjectWriter writer : partitionWriters) {
              // This method explicitly does _not_ throw exceptions:
              File file = partitionWriter.revertPartialWritesAndClose();
              if (!file.delete()) {
                logger.error("Error while deleting file {}", file.getAbsolutePath());
              }
            // }
          } finally {
            partitionWriter = null;
          }
        }
        return None$.empty();
      }
    }
  }
}
