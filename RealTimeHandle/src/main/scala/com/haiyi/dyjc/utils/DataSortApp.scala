package com.haiyi.dyjc.utils

import java.text.SimpleDateFormat

import org.apache.flink.api.common.operators.Order
import org.apache.flink.api.scala.ExecutionEnvironment
import org.apache.flink.api.scala._


/**
 * @author Mr.Xu
 * @create 2020-09-09
 *  处理数据， 使数据有序。
 *  由于之前的bjl_new_power表的数据是批处理过来的数据，所以无序。
 *  实际的数据是有序的， 这里对数据进行排序。
 *  试用一下Flink的批处理
 */
object DataSortApp {
  def main(args: Array[String]): Unit = {
    val env = ExecutionEnvironment.createCollectionsEnvironment
    env.setParallelism(1)
    val input = env.readTextFile("D:\\Work\\电压监测测试Flink\\部分数据\\b_jl_new_power.txt")
    val mapTemp = input.map(line => {
      val words = line.split("#")
      val sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      val ts = sdf.parse(words(0)).getTime
      (ts, line)
    })

    mapTemp.sortPartition(1, Order.ASCENDING).map(_._2).writeAsText("D:\\Work\\电压监测测试Flink\\部分数据\\b_jl_new_power_sort.txt")
    env.execute("DataSortApp")
  }

}
