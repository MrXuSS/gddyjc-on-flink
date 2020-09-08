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
 */
object LongTimeOverLoadApp {

  def main(args: Array[String]): Unit = {

    val env: StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    env.setParallelism(5)

    env.setStateBackend(new RocksDBStateBackend("file:///D:/Program/WorkSpace/IDEA_WorkSpace/gddyjc-on-flink/RockDBState"))

    val bjlTransformerSource: BjlTransformerSource = new BjlTransformerSource

    val bjlTransformerStream: DataStream[BjlTransformer] = env.addSource(bjlTransformerSource)
    val bjlNewPowerStream: DataStream[BjlNewPower] = env.addSource(new BjlNewPowerSource)

    // 根据测量点标识进行关联
    bjlTransformerStream.keyBy(_.MP_ID)
      .connect(bjlNewPowerStream.keyBy(_.CLDBS))
        .process(new TransformerConnectNewPower)
//        .print()

    env.execute("LongTimeOverLoadApp")
  }


}
