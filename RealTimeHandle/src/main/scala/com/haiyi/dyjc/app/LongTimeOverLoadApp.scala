package com.haiyi.dyjc.app

import com.haiyi.dyjc.entity.Sources.{BjlNewPowerSource, BjlTransformerSource}
import com.haiyi.dyjc.entity.{BjlNewPower, BjlTransformer}
import com.haiyi.dyjc.utils.TransformerConnectNewPower
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.scala._

/**
 * @author Mr.Xu
 * @create 2020-09-07
 *
 *         长时过载有五种情况
 *         （1）负载率超过120，连续过载时间超过8小时 480
 *         （2）负载率超过130，连续过载时间超过4小时 240
 *         （3）负载率超过150，连续过载时间超过2小时 120
 *         （4）负载率超过160，连续过载时间超过1小时 60
 *         （5）负载率超过180，连续过载时间超过0.5小时 30
 *
 *         问题1： 负载率如果在两个阶段之间来回跳跃 例如：121 131 121 131 121 131 该警告是否应该在120范围内给出？
 *         解决方式1： 使用120范围来包含130范围， 即大范围包含小范围
 *         衍生问题： 大范围包含小范围，若数据一直是大于130， 120包含130的数据， 造成数据的重复输出。
 *         解决方式2： 只统计在各个数据段的数据。
 *         衍生问题： 若数据来回跳动， 就会造成最终的结果不准确。
 *              (数据丢失， 如例：121 131 121 131， 两个阶段都不认为该数据在自己的范围内连续)。
 *
 *         现采取解决方式1.  已解决数据重复问题
 */

object LongTimeOverLoadApp {

  def main(args: Array[String]): Unit = {

    val env: StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    env.setParallelism(10)

    env.setStateBackend(new RocksDBStateBackend("file:///D:/Program/WorkSpace/IDEA_WorkSpace/gddyjc-on-flink/RockDBState"))

    val bjlTransformerSource: BjlTransformerSource = new BjlTransformerSource

    val bjlTransformerStream: DataStream[BjlTransformer] = env.addSource(bjlTransformerSource)
    val bjlNewPowerStream: DataStream[BjlNewPower] = env.addSource(new BjlNewPowerSource)

    // 根据测量点标识进行关联
    bjlTransformerStream.keyBy(_.MP_ID)
      .connect(bjlNewPowerStream.keyBy(_.CLDBS))
        .process(new TransformerConnectNewPower)
        .print()

    env.execute("LongTimeOverLoadApp")
  }
}
