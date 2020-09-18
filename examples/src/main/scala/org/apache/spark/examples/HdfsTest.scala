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

// scalastyle:off println
package org.apache.spark.examples

import org.apache.spark.sql.SparkSession

/**
 * Spark计算结果继续追加在HDFS目录下，不会覆盖之前的文件: https://blog.csdn.net/zmc921/article/details/74948786
 *   https://blog.csdn.net/zmc921/category_7020046.html
 * spark读取hdfs上的文件和写入数据到hdfs上面: https://www.cnblogs.com/VIP8/p/10447236.html
 * sparksql读取hive中的数据保存到hdfs中: https://blog.csdn.net/u012719230/article/details/82492745
 * sparkSQL 写数据到MySQL的几种模式解释以及overwrite模式在不删除表结构的情况下的实现:https://blog.csdn.net/qq_42012160/article/details/88816985
 * spark遇到的问题（持续更新）: https://www.cnblogs.com/carsonwuu/p/11207083.html
 * spark将计算结果写入到hdfs的两种方法：https://www.cnblogs.com/luckuan/p/5252580.html
 * Kafka+Spark streaming读取数据存hdfs：https://blog.csdn.net/qq_25908611/article/details/80921205
 *     解决Spark Streaming写入HDFS的小文件问题： https://www.jianshu.com/p/372105903e75
 * 是时候放弃 Spark Streaming, 转向 Structured Streaming 了：https://zhuanlan.zhihu.com/p/51883927
 * 实战|使用Spark Structured Streaming写入Hudi：https://www.cnblogs.com/leesf456/p/12728603.html?utm_source=tuicool&utm_medium=referral
 * Spark Structured Streaming：将数据落地按照数据字段进行分区方案：https://www.cnblogs.com/yy3b2007com/p/9776876.html
 * 10.4 spark2 structured streaming 实时计算hdfs文件输入流cdh：https://blog.csdn.net/kk25114/article/details/98777468
 * Spark Structured Streaming 写checkpoint 到HDFS 抖动的排查：https://www.jianshu.com/p/86c81db326e1
 */
object HdfsTest {

  /** Usage: HdfsTest [file] */
  def main(args: Array[String]) {
    if (args.length < 1) {
      System.err.println("Usage: HdfsTest <file>")
      System.exit(1)
    }
    val spark = SparkSession
      .builder
      .appName("HdfsTest")
      .getOrCreate()
    val file = spark.read.text(args(0)).rdd
    val mapped = file.map(s => s.length).cache()
    // TODO RDD.saveAsTextFile -> PairRDDFunctions.saveAsHadoopFile
    //   -> PairRDDFunctions.saveAsHadoopDataset -> SparkHadoopWriter.write ->
    mapped.saveAsTextFile("hdfs:///tmp/test.txt")
    for (iter <- 1 to 10) {
      val start = System.currentTimeMillis()
      for (x <- mapped) { x + 2 }
      val end = System.currentTimeMillis()
      println(s"Iteration $iter took ${end-start} ms")
    }
    spark.stop()
  }
}
// scalastyle:on println
