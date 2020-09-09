package com.haiyi.dyjc.utils

import java.text.SimpleDateFormat

import com.haiyi.dyjc.entity.{BjlNewPower, BjlTransformer, LoadResult, LongTimeLoadView}
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor, MapState, MapStateDescriptor}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.co.CoProcessFunction
import org.apache.flink.util.Collector

import scala.collection.JavaConversions._

/**
 * @author Mr.Xu
 * @create 2020-09-08
 *  自定义Function : 处理两条流的连接， 档案表使用MpaState全部存储； 实时功率表数据来时与State的数据进行关联计算
 *  !!!!!!!! 急待优化 : 牵扯到太多的State的读写。
 *  考虑将每个负载率段的最小时间在open()进行读入内存，在内存中对值进行修改，速度应该会提升不少！！！！
 */

class TransformerConnectNewPower extends CoProcessFunction[BjlTransformer, BjlNewPower, LongTimeLoadView]{

  // 用来存储档案表的数据
  lazy val bjlTransformerState: MapState[String, BjlTransformer] = getRuntimeContext.getMapState(
    new MapStateDescriptor[String, BjlTransformer]("bjlTransformerState", TypeInformation.of(classOf[String]), TypeInformation.of(classOf[BjlTransformer]))
  )

  // 保存负载率在(120-130]数据 （MP_ID， 负载率， ts）
  lazy val load120State: ListState[LoadResult] = getRuntimeContext.getListState(
    new ListStateDescriptor[LoadResult]("load120State", TypeInformation.of(classOf[LoadResult]))
  )
  // 保存负载率在(130-150]的数据 （MP_ID， 负载率， ts）
  lazy val load130State: ListState[LoadResult] = getRuntimeContext.getListState(
    new ListStateDescriptor[LoadResult]("load130State", TypeInformation.of(classOf[LoadResult]))
  )
  // 保存负载率在(150-160]的数据 （MP_ID， 负载率， ts）
  lazy val load150State: ListState[LoadResult] = getRuntimeContext.getListState(
    new ListStateDescriptor[LoadResult]("load150State", TypeInformation.of(classOf[LoadResult]))
  )
  // 保存负载率在(160-180]的数据 （MP_ID， 负载率， ts）
  lazy val load160State: ListState[LoadResult] = getRuntimeContext.getListState(
    new ListStateDescriptor[LoadResult]("load160State", TypeInformation.of(classOf[LoadResult]))
  )
  // 保存负载率在180++的数据 （MP_ID， 负载率， ts）
  lazy val load180State: ListState[LoadResult] = getRuntimeContext.getListState(
    new ListStateDescriptor[LoadResult]("load180State", TypeInformation.of(classOf[LoadResult]))
  )

  override def open(parameters: Configuration): Unit = {

  }

  // 标识各阶段负载率数据的最小时间戳
  var minTime120: Long = -1
  var minTime130: Long = -1
  var minTime140: Long = -1
  var minTime160: Long = -1
  var minTime180: Long = -1

  // 第一条流处理档案表，将数据直接直接存入State
  override def processElement1(value: BjlTransformer,
                               ctx: CoProcessFunction[BjlTransformer, BjlNewPower, LongTimeLoadView]#Context,
                               out: Collector[LongTimeLoadView]): Unit = {

    bjlTransformerState.put(value.MP_ID, value)
  }

  // 第二条流处理实时功率流， 关联档案表, 计算负载率
  override def processElement2(value: BjlNewPower,
                               ctx: CoProcessFunction[BjlTransformer, BjlNewPower, LongTimeLoadView]#Context,
                               out: Collector[LongTimeLoadView]): Unit = {
    val bjlTransformer: BjlTransformer = bjlTransformerState.get(value.CLDBS)
    if(bjlTransformer != null) {

      val sz1: Double = math.sqrt((value.ZYGGL * value.ZYGGL) + (value.ZWGGL * value.ZWGGL)) * bjlTransformer.BL
      val sz2: Double = math.sqrt((value.AZXYG + value.BZXYG + value.CZXYG) + (value.AZXWG + value.BZXWG + value.CZXWG)) * bjlTransformer.BL

      val loadFactor: Double = math.max(sz1, sz2) / bjlTransformer.CAPACITY.toLong
      // SJSJ 2018-12-18 07:15:00
      val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      val ts: Long = sdf.parse(value.SJSJ).getTime
      val loadResult: LoadResult = LoadResult(value.CLDBS, loadFactor, ts)
      if (loadFactor <= 120) {
        clearState()
      } else if (loadFactor > 180) {
        load120State.add(loadResult)
        load130State.add(loadResult)
        load150State.add(loadResult)
        load160State.add(loadResult)
        load180State.add(loadResult)
      } else if (loadFactor > 160) {
        load120State.add(loadResult)
        load130State.add(loadResult)
        load150State.add(loadResult)
        load160State.add(loadResult)
        load180State.clear()
      } else if (loadFactor > 150) {
        load120State.add(loadResult)
        load130State.add(loadResult)
        load150State.add(loadResult)
        load160State.clear()
        load180State.clear()
      } else if (loadFactor > 130) {
        load120State.add(loadResult)
        load130State.add(loadResult)
        load150State.clear()
        load160State.clear()
        load180State.clear()
      } else if (loadFactor > 120) {
        load120State.add(loadResult)
        load130State.clear()
        load150State.clear()
        load160State.clear()
        load180State.clear()
      }

      // 每来一条数据就会运行一次， 考虑先抽离出最小时间戳
//      val list120: List[LoadResult] = load120State.get().toList
//      val list130: List[LoadResult] = load130State.get().toList
//      val list140: List[LoadResult] = load150State.get().toList
//      val list160: List[LoadResult] = load160State.get().toList
//      val list180: List[LoadResult] = load180State.get().toList


//      // 新来的数据和list中的第一条数据的时间进行比较， 若大于最大限度时间就输出。
//      if(!list120.isEmpty && ts - list120(0).ts >= 8 * 60 * 60 * 1000 ) {
//        out.collect(LongTimeLoadView(bjlTransformer.MP_ID, list120.map(_.LoadFactor).min, list120.map(_.LoadFactor).max, "long_load_120"))
//      }
//
//      if(!list130.isEmpty  && ts - list130(0).ts >= 4 * 60 * 60 * 1000 ) {
//        out.collect(LongTimeLoadView(bjlTransformer.MP_ID, list130.map(_.LoadFactor).min, list120.map(_.LoadFactor).max, "long_load_130"))
//      }
//
//      if(!list140.isEmpty  && ts - list140(0).ts >= 2 * 60 * 60 * 1000 ) {
//        out.collect(LongTimeLoadView(bjlTransformer.MP_ID, list140.map(_.LoadFactor).min, list120.map(_.LoadFactor).max, "long_load_140"))
//      }
//
//      if(!list160.isEmpty  && ts - list160(0).ts >= 1 * 60 * 60 * 1000 ) {
//        out.collect(LongTimeLoadView(bjlTransformer.MP_ID, list160.map(_.LoadFactor).min, list120.map(_.LoadFactor).max, "long_load_160"))
//      }
//
//      if(!list180.isEmpty  && ts - list180(0).ts >= 0.5 * 60 * 60 * 1000 ) {
//        out.collect(LongTimeLoadView(bjlTransformer.MP_ID, list180.map(_.LoadFactor).min, list120.map(_.LoadFactor).max, "long_load_180"))
//      }

//      out.collect(loadResult.toString)

      var load120Head: LoadResult = null
      var load130Head: LoadResult = null
      var load150Head: LoadResult = null
      var load160Head: LoadResult = null
      var load180Head: LoadResult = null

      if(load180State.get().iterator().hasNext) {
        load180Head = load180State.get().iterator().next()
      }
      // 解决数据重复问题， 先判断范围小的数据， 当负载率超过180持续时间达到最大限制时，删除120 130 150 160大范围的数据，输出最严重的负载率报警
      // 新来的数据和list中的第一条数据的时间进行比较， 若大于最大限度时间就输出。
      if(load180Head != null && ts - load180Head.ts >= 0.5 * 60 * 60 * 1000 ) {
        val list180 = load180State.get().toList
        load120State.clear()
        load130State.clear()
        load150State.clear()
        load160State.clear()
        out.collect(LongTimeLoadView(bjlTransformer.MP_ID, list180.map(_.LoadFactor).min, list180.map(_.LoadFactor).max, "long_load_180"))
      }

      if(load160State.get().iterator().hasNext) {
        load160Head = load160State.get().iterator().next()
      }

      if(load160Head != null && ts - load160Head.ts >= 1 * 60 * 60 * 1000 ) {
        val list160 = load160State.get().toList
        load120State.clear()
        load130State.clear()
        load150State.clear()
        out.collect(LongTimeLoadView(bjlTransformer.MP_ID, list160.map(_.LoadFactor).min, list160.map(_.LoadFactor).max, "long_load_160"))
      }

      if(load150State.get().iterator().hasNext) {
        load150Head = load150State.get().iterator().next()
      }

      if(load150Head != null && ts - load150Head.ts >= 2 * 60 * 60 * 1000 ) {
        val list150 = load150State.get().toList
        load130State.clear()
        load120State.clear()
        out.collect(LongTimeLoadView(bjlTransformer.MP_ID, list150.map(_.LoadFactor).min, list150.map(_.LoadFactor).max, "long_load_140"))
      }

      if(load130State.get().iterator().hasNext) {
        load130Head = load130State.get().iterator().next()
      }

      if(load130Head != null && ts - load130Head.ts >= 4 * 60 * 60 * 1000 ) {
        val list130 = load130State.get().toList
        load120State.clear()
        out.collect(LongTimeLoadView(bjlTransformer.MP_ID, list130.map(_.LoadFactor).min, list130.map(_.LoadFactor).max, "long_load_130"))
      }

      if(load120State.get().iterator().hasNext) {
        load120Head = load120State.get().iterator().next()
      }

      if(load120Head != null && ts - load120Head.ts >= 8 * 60 * 60 * 1000 ) {
        val list120 = load120State.get().toList
        out.collect(LongTimeLoadView(bjlTransformer.MP_ID, list120.map(_.LoadFactor).min, list120.map(_.LoadFactor).max, "long_load_120"))
      }
    }

  }

  def clearState(): Unit = {
    load120State.clear()
    load130State.clear()
    load150State.clear()
    load160State.clear()
    load180State.clear()
  }

}
