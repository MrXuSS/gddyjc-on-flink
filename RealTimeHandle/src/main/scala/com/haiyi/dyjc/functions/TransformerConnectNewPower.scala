package com.haiyi.dyjc.functions

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
 *  读State的操作不能再open()方法中使用， 报错 No keySet，This method should not be called outside of a keyed context.
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
      //  b_jl_new_power 表中含有视在功率字段， 但是存在空字符串脏数据， 这里直接计算得出。
      val sz1: Double = math.sqrt((value.ZYGGL * value.ZYGGL) + (value.ZWGGL * value.ZWGGL)) * bjlTransformer.BL
      val sz2: Double = math.sqrt((value.AZXYG + value.BZXYG + value.CZXYG) + (value.AZXWG + value.BZXWG + value.CZXWG)) * bjlTransformer.BL
      val loadFactor: Double = math.max(sz1, sz2) / bjlTransformer.CAPACITY.toLong
      // SJSJ 2018-12-18 07:15:00
      val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      val ts: Long = sdf.parse(value.SJSJ).getTime
      val loadResult: LoadResult = LoadResult(value.CLDBS, loadFactor, ts)

      if (loadFactor <= 120) {
        clearState(List(120, 130, 150, 160, 180))
      } else if (loadFactor > 180) {
        addState(List(120, 130, 150, 160, 180), loadResult, ts)
      } else if (loadFactor > 160) {
        addState(List(120, 130, 150, 160), loadResult, ts)
        clearState(List(180))
      } else if (loadFactor > 150) {
        addState(List(120, 130, 150), loadResult, ts)
        clearState(List(160, 180))
      } else if (loadFactor > 130) {
        addState(List(120, 130), loadResult, ts)
        clearState(List(150, 160, 180))
      } else if (loadFactor > 120) {
        addState(List(120), loadResult, ts)
        clearState(List(130, 150, 160, 180))
      }

      var load120Head: LoadResult = null
      var load130Head: LoadResult = null
      var load150Head: LoadResult = null
      var load160Head: LoadResult = null
      var load180Head: LoadResult = null

      if(load180State.get().iterator().hasNext) {
        load180Head = load180State.get().iterator().next()
      }

      // 解决数据重复问题， 先判断范围小的数据， 当负载率超过180持续时间达到最大限制时，删除120 130 150 160大范围的数据，输出最严重的负载率报警
      if(load180Head != null && ts - load180Head.ts >= 0.5 * 60 * 60 * 1000 ) {
        val list180 = load180State.get().toList
        clearState(List(120, 130, 150, 160))
        out.collect(LongTimeLoadView(bjlTransformer.MP_ID,
                                      list180.map(_.LoadFactor).min,
                                      list180.map(_.LoadFactor).max,
                                      "long_load_180"))
      }

      if(load160State.get().iterator().hasNext) {
        load160Head = load160State.get().iterator().next()
      }

      if(load160Head != null && ts - load160Head.ts >= 1 * 60 * 60 * 1000 ) {
        val list160 = load160State.get().toList
        clearState(List(120, 130, 150))
        out.collect(LongTimeLoadView(bjlTransformer.MP_ID,
                                    list160.map(_.LoadFactor).min,
                                    list160.map(_.LoadFactor).max,
                                    "long_load_160"))
      }

      if(load150State.get().iterator().hasNext) {
        load150Head = load150State.get().iterator().next()
      }

      if(load150Head != null && ts - load150Head.ts >= 2 * 60 * 60 * 1000 ) {
        val list150 = load150State.get().toList
        clearState(List(120, 130))
        out.collect(LongTimeLoadView(bjlTransformer.MP_ID,
                                    list150.map(_.LoadFactor).min,
                                    list150.map(_.LoadFactor).max,
                                    "long_load_140"))
      }

      if(load130State.get().iterator().hasNext) {
        load130Head = load130State.get().iterator().next()
      }

      if(load130Head != null && ts - load130Head.ts >= 4 * 60 * 60 * 1000 ) {
        val list130 = load130State.get().toList
        clearState(List(120))
        out.collect(LongTimeLoadView(bjlTransformer.MP_ID,
                                      list130.map(_.LoadFactor).min,
                                      list130.map(_.LoadFactor).max,
                                      "long_load_130"))
      }

      if(load120State.get().iterator().hasNext) {
        load120Head = load120State.get().iterator().next()
      }

      if(load120Head != null && ts - load120Head.ts >= 8 * 60 * 60 * 1000 ) {
        val list120 = load120State.get().toList
        out.collect(LongTimeLoadView(bjlTransformer.MP_ID,
                                    list120.map(_.LoadFactor).min,
                                    list120.map(_.LoadFactor).max,
                                    "long_load_120"))
      }
    }
  }

  /**
   * 根据传入的list对状态进行清空,并将清除的负载率段的最小时间设成 -1
   * @param nums 需要清空状态的编码
   */
  def clearState(nums: List[Int]): Unit = {
    for (num <- nums) {
      num match {
        case 120 => load120State.clear()
        case 130 => load130State.clear()
        case 150 => load150State.clear()
        case 160 => load160State.clear()
        case 180 => load180State.clear()
        // scalastyle:off println
        case _ => println("输入的负载率阶段数值错误！！！")
        // scalastyle:on println
      }
    }
  }

  /**
   * 通过传入的list，和需要添加的数值， 对状态进行添加
   * @param nums 根据传入的list对状态进行存储
   * @param loadResult 需要存储的数据
   */
    def addState(nums: List[Int], loadResult: LoadResult, ts: Long): Unit = {
      for (num <- nums) {
        num match {
          case 120 => load120State.add(loadResult)
          case 130 => load130State.add(loadResult)
          case 150 => load150State.add(loadResult)
          case 160 => load160State.add(loadResult)
          case 180 => load180State.add(loadResult)
          // scalastyle:off println
          case _ => println("输入的负载率阶段数值错误！！！")
          // scalastyle:on println
        }
      }
    }

 }
