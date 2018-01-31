/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/
package org.apache.griffin.measure.data.source.cache

import java.util.concurrent.TimeUnit

import org.apache.griffin.measure.cache.info.{InfoCacheInstance, TimeInfoCache}
import org.apache.griffin.measure.cache.tmst.TmstCache
import org.apache.griffin.measure.log.Loggable
import org.apache.griffin.measure.process.temp.TimeRange
import org.apache.griffin.measure.utils.ParamUtil._
import org.apache.griffin.measure.utils.{HdfsUtil, TimeUtil}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame

abstract class DataSourceCache(param: Map[String, Any], dsName: String, index: Int
                              ) extends DataCacheable with Loggable with Serializable {

//  val param: Map[String, Any]
//  val dsName: String
//  val index: Int

  var tmstCache: TmstCache = _
  protected def rangeTmsts(from: Long, until: Long) = tmstCache.range(from, until)
  protected def clearTmst(t: Long) = tmstCache.remove(t)
  protected def clearTmstsUntil(until: Long) = {
    val outDateTmsts = tmstCache.until(until)
    tmstCache.remove(outDateTmsts)
  }

  val _FilePath = "file.path"
  val _InfoPath = "info.path"
  val _ReadyTimeInterval = "ready.time.interval"
  val _ReadyTimeDelay = "ready.time.delay"
  val _TimeRange = "time.range"

  val defFilePath = s"/griffin/cache/${dsName}/${index}"
  val defInfoPath = s"${index}"

  val filePath: String = param.getString(_FilePath, defFilePath)
  val cacheInfoPath: String = param.getString(_InfoPath, defInfoPath)
  val readyTimeInterval: Long = TimeUtil.milliseconds(param.getString(_ReadyTimeInterval, "1m")).getOrElse(60000L)
  val readyTimeDelay: Long = TimeUtil.milliseconds(param.getString(_ReadyTimeDelay, "1m")).getOrElse(60000L)
  val deltaTimeRange: (Long, Long) = {
    def negative(n: Long): Long = if (n <= 0) n else 0
    param.get(_TimeRange) match {
      case Some(seq: Seq[String]) => {
        val nseq = seq.flatMap(TimeUtil.milliseconds(_))
        val ns = negative(nseq.headOption.getOrElse(0))
        val ne = negative(nseq.tail.headOption.getOrElse(0))
        (ns, ne)
      }
      case _ => (0, 0)
    }
  }

  val _ReadOnly = "read.only"
  val readOnly = param.getBoolean(_ReadOnly, false)

//  val rowSepLiteral = "\n"
  val partitionUnits: List[String] = List("hour", "min", "sec")
  val minUnitTime: Long = TimeUtil.timeFromUnit(1, partitionUnits.last)

  val newCacheLock = InfoCacheInstance.genLock(s"${cacheInfoPath}.new")
  val oldCacheLock = InfoCacheInstance.genLock(s"${cacheInfoPath}.old")

  protected def saveDataFrame(df: DataFrame, path: String): Unit
  protected def saveDataList(arr: Iterable[String], path: String): Unit
  protected def readDataFrame(paths: Seq[String]): Option[DataFrame]
  protected def removeDataPath(path: String): Unit

  def init(): Unit = {}

  def saveData(dfOpt: Option[DataFrame], ms: Long): Unit = {
    if (!readOnly) {
      dfOpt match {
        case Some(df) => {
//          val newCacheLocked = newCacheLock.lock(-1, TimeUnit.SECONDS)
//          if (newCacheLocked) {
            try {
              val dataFilePath = getDataFilePath(ms)

              // save data
              saveDataFrame(df, dataFilePath)
            } catch {
              case e: Throwable => error(s"save data error: ${e.getMessage}")
            } finally {
              newCacheLock.unlock()
            }
//          }
        }
        case _ => {
          info(s"no data frame to save")
        }
      }

      // submit cache time and ready time
      submitCacheTime(ms)
      submitReadyTime(ms)
    }
  }

  // return: (data frame option, time range)
  def readData(): (Option[DataFrame], TimeRange) = {
    val tr = TimeInfoCache.getTimeRange
    val timeRange = (tr._1 + minUnitTime, tr._2)
    submitLastProcTime(timeRange._2)

    val reviseTimeRange = (timeRange._1 + deltaTimeRange._1, timeRange._2 + deltaTimeRange._2)
    submitCleanTime(reviseTimeRange._1)

    // read directly through partition info
    val partitionRanges = getPartitionRange(reviseTimeRange._1, reviseTimeRange._2)
    println(s"read time ranges: ${reviseTimeRange}")
    println(s"read partition ranges: ${partitionRanges}")

    // list partition paths
    val partitionPaths = listPathsBetweenRanges(filePath :: Nil, partitionRanges)

    val dfOpt = if (partitionPaths.isEmpty) {
      None
    } else {
      try {
        readDataFrame(partitionPaths)
      } catch {
        case e: Throwable => {
          warn(s"read data source cache warn: ${e.getMessage}")
          None
        }
      }
    }

    // from until tmst range
    val (from, until) = (reviseTimeRange._1, reviseTimeRange._2 + 1)
    val tmstSet = rangeTmsts(from, until)

    val retTimeRange = TimeRange(reviseTimeRange, tmstSet)
    (dfOpt, retTimeRange)
  }

  // not used actually
  def updateData(df: DataFrame, ms: Long): Unit = {
//    if (!readOnly) {
//      val ptns = getPartition(ms)
//      val ptnsPath = genPartitionHdfsPath(ptns)
//      val dirPath = s"${filePath}/${ptnsPath}"
//      val dataFileName = s"${ms}"
//      val dataFilePath = HdfsUtil.getHdfsFilePath(dirPath, dataFileName)
//
//      try {
//        val records = df.toJSON
//        val arr = records.collect
//        val needSave = !arr.isEmpty
//
//        // remove out time old data
//        HdfsFileDumpUtil.remove(dirPath, dataFileName, true)
//        println(s"remove file path: ${dirPath}/${dataFileName}")
//
//        // save updated data
//        if (needSave) {
//          HdfsFileDumpUtil.dump(dataFilePath, arr, rowSepLiteral)
//          println(s"update file path: ${dataFilePath}")
//        } else {
//          clearTmst(ms)
//          println(s"data source [${dsName}] timestamp [${ms}] cleared")
//        }
//      } catch {
//        case e: Throwable => error(s"update data error: ${e.getMessage}")
//      }
//    }
  }

  // in update data map (not using now)
  def updateData(rdd: RDD[String], ms: Long, cnt: Long): Unit = {
//    if (!readOnly) {
//      val ptns = getPartition(ms)
//      val ptnsPath = genPartitionHdfsPath(ptns)
//      val dirPath = s"${filePath}/${ptnsPath}"
//      val dataFileName = s"${ms}"
//      val dataFilePath = HdfsUtil.getHdfsFilePath(dirPath, dataFileName)
//
//      try {
//        //      val needSave = !rdd.isEmpty
//
//        // remove out time old data
//        removeDatPath(dataFilePath)
////        HdfsFileDumpUtil.remove(dirPath, dataFileName, true)
//        println(s"remove file path: ${dataFilePath}")
//
//        // save updated data
//        if (cnt > 0) {
//          saveDataRdd(dataFilePath)
////          HdfsFileDumpUtil.dump(dataFilePath, rdd, rowSepLiteral)
//          println(s"update file path: ${dataFilePath}")
//        } else {
//          clearTmst(ms)
//          println(s"data source [${dsName}] timestamp [${ms}] cleared")
//        }
//      } catch {
//        case e: Throwable => error(s"update data error: ${e.getMessage}")
//      } finally {
//        rdd.unpersist()
//      }
//    }
  }

  // in streaming mode
  def updateData(arr: Iterable[String], ms: Long): Unit = {
    if (!readOnly) {
      val dataFilePath = getDataFilePath(ms)

      try {
        val needSave = !arr.isEmpty

        // remove out time old data
        removeDataPath(dataFilePath)
//        HdfsFileDumpUtil.remove(dirPath, dataFileName, true)
        println(s"remove file path: ${dataFilePath}")

        // save updated data
        if (needSave) {
          saveDataList(arr, dataFilePath)
//          HdfsFileDumpUtil.dump(dataFilePath, arr, rowSepLiteral)
          println(s"update file path: ${dataFilePath}")
        } else {
          clearTmst(ms)
          println(s"data source [${dsName}] timestamp [${ms}] cleared")
        }
      } catch {
        case e: Throwable => error(s"update data error: ${e.getMessage}")
      }
    }
  }

  def updateDataMap(dfMap: Map[Long, DataFrame]): Unit = {
//    if (!readOnly) {
//      val dataMap = dfMap.map { pair =>
//        val (t, recs) = pair
//        val rdd = recs.toJSON
//        //      rdd.cache
//        (t, rdd, rdd.count)
//      }
//
//      dataMap.foreach { pair =>
//        val (t, arr, cnt) = pair
//        updateData(arr, t, cnt)
//      }
//    }
  }

  def cleanOldData(): Unit = {
    if (!readOnly) {
//      val oldCacheLocked = oldCacheLock.lock(-1, TimeUnit.SECONDS)
//      if (oldCacheLocked) {
        try {
          val cleanTime = readCleanTime()
          cleanTime match {
            case Some(ct) => {
              println(s"data source [${dsName}] old timestamps clear until [${ct}]")

              // clear out date tmsts
              clearTmstsUntil(ct)

              // drop partitions
              val bounds = getPartition(ct)

              // list partition paths
              val earlierPaths = listPathsEarlierThanBounds(filePath :: Nil, bounds)

              // delete out time data path
              earlierPaths.foreach { path =>
                removeDataPath(path)
              }
            }
            case _ => {
              // do nothing
            }
          }
        } catch {
          case e: Throwable => error(s"clean old data error: ${e.getMessage}")
        } finally {
          oldCacheLock.unlock()
        }
//      }
    }
  }

  override protected def genCleanTime(ms: Long): Long = {
    val minPartitionUnit = partitionUnits.last
    val t1 = TimeUtil.timeToUnit(ms, minPartitionUnit)
    val t2 = TimeUtil.timeFromUnit(t1, minPartitionUnit)
    t2
  }

  private def getPartition(ms: Long): List[Long] = {
    partitionUnits.map { unit =>
      TimeUtil.timeToUnit(ms, unit)
    }
  }
  private def getPartitionRange(ms1: Long, ms2: Long): List[(Long, Long)] = {
    getPartition(ms1).zip(getPartition(ms2))
  }
  private def genPartitionHdfsPath(partition: List[Long]): String = {
    partition.map(prtn => s"${prtn}").mkString("/")
  }
  private def str2Long(str: String): Option[Long] = {
    try {
      Some(str.toLong)
    } catch {
      case e: Throwable => None
    }
  }

  private def getDataFilePath(ms: Long): String = {
    val ptns = getPartition(ms)
    val ptnsPath = genPartitionHdfsPath(ptns)
    val dirPath = s"${filePath}/${ptnsPath}"
    val dataFileName = s"${ms}"
    val dataFilePath = HdfsUtil.getHdfsFilePath(dirPath, dataFileName)
    dataFilePath
  }


  // here the range means [min, max]
  private def listPathsBetweenRanges(paths: List[String],
                                     partitionRanges: List[(Long, Long)]
                                    ): List[String] = {
    partitionRanges match {
      case Nil => paths
      case head :: tail => {
        val (lb, ub) = head
        val curPaths = paths.flatMap { path =>
          val names = HdfsUtil.listSubPathsByType(path, "dir").toList
          names.filter { name =>
            str2Long(name) match {
              case Some(t) => (t >= lb) && (t <= ub)
              case _ => false
            }
          }.map(HdfsUtil.getHdfsFilePath(path, _))
        }
        listPathsBetweenRanges(curPaths, tail)
      }
    }
  }
  private def listPathsEarlierThanBounds(paths: List[String], bounds: List[Long]
                                        ): List[String] = {
    bounds match {
      case Nil => paths
      case head :: tail => {
        val earlierPaths = paths.flatMap { path =>
          val names = HdfsUtil.listSubPathsByType(path, "dir").toList
          names.filter { name =>
            str2Long(name) match {
              case Some(t) => (t < head)
              case _ => false
            }
          }.map(HdfsUtil.getHdfsFilePath(path, _))
        }
        val equalPaths = paths.flatMap { path =>
          val names = HdfsUtil.listSubPathsByType(path, "dir").toList
          names.filter { name =>
            str2Long(name) match {
              case Some(t) => (t == head)
              case _ => false
            }
          }.map(HdfsUtil.getHdfsFilePath(path, _))
        }

        tail match {
          case Nil => earlierPaths
          case _ => earlierPaths ::: listPathsEarlierThanBounds(equalPaths, tail)
        }
      }
    }
  }

}