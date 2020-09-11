package com.haiyi.dyjc.functions

import java.text.SimpleDateFormat

import com.haiyi.dyjc.entity.{BjlNewPower, BjlTransformer, LoadLastTimeAndDurTime, LoadResult, LongTimeLoadView}
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor, MapState, MapStateDescriptor, ValueState, ValueStateDescriptor}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.co.CoProcessFunction
import org.apache.flink.util.Collector

import scala.collection.JavaConversions._

/**
 * @author Mr.Xu
 * @create 2020-09-08
 *  自定义Function : 处理两条流的连接， 档案表使用ValueState存储； 实时功率表数据来时与State的数据进行关联计算
 *
 *  对第一版的优化， 将存储分阶段负载率的数据结构进行转换， 不在全量存储在listState
 *  读State的操作不能再open()方法中使用， 报错 No keySet，This method should not be called outside of a keyed context.
 */

class TransformerConnectNewPower2 extends CoProcessFunction[BjlTransformer, BjlNewPower, LongTimeLoadView]{

  // 用来存储档案表的数据
  // keyby connect 含有相同Key的数据转发到下游同一个算子实例上。
  // 所以说之前的MapState中只含有一条数据， 可以用ValueState代替
  lazy val bjlTransformerState: ValueState[BjlTransformer] = getRuntimeContext.getState(
    new ValueStateDescriptor[BjlTransformer](
      "bjlTransformerState",
      TypeInformation.of(classOf[BjlTransformer]))
  )

  // 用来存储各阶段负载率的值
  // LoadLastTimeAndDurTime( MP_ID: String,
  //                         var LAST_TIME: Long,  // 上次时间
  //                         var DUR_TIME: Long, // 持续时间
  //                         var MIN_VALUE: Double, //记录当前负载率阶段的最小值
  //                         var MAX_VALUE: Double //记录当前负载率阶段的最小值))
  // 例如： (120, LoadLastTimeAndDurTime) (130, LoadLastTimeAndDurTime)
  lazy val loadLastTimeAndDurTimeMapState: MapState[String, LoadLastTimeAndDurTime] = getRuntimeContext.getMapState(
    new MapStateDescriptor[String, LoadLastTimeAndDurTime](
      "LoadLastTimeAndDurTimeMapState",
      TypeInformation.of(classOf[String]),
      TypeInformation.of(classOf[LoadLastTimeAndDurTime])
    )
  )

  // 用来标记每个负载率阶段连续持续的时间长短
  var durTime120: Long = 0
  var durTime130: Long = 0
  var durTime150: Long = 0
  var durTime160: Long = 0
  var durTime180: Long = 0
  // 判断是否为第一运行
  var isFirst: Boolean = true

  override def open(parameters: Configuration): Unit = {

  }

  // 第一条流处理档案表，将数据直接直接存入ValueState
  override def processElement1(value: BjlTransformer,
                               ctx: CoProcessFunction[BjlTransformer, BjlNewPower, LongTimeLoadView]#Context,
                               out: Collector[LongTimeLoadView]): Unit = {

    bjlTransformerState.update(value)
  }

  // 第二条流处理实时功率流， 关联档案表, 计算负载率
  override def processElement2(value: BjlNewPower,
                               ctx: CoProcessFunction[BjlTransformer, BjlNewPower, LongTimeLoadView]#Context,
                               out: Collector[LongTimeLoadView]): Unit = {

    if ( isFirst) {
      initDurTime()
      isFirst = false
    }

    val bjlTransformer: BjlTransformer = bjlTransformerState.value()
    if(bjlTransformer != null) {
      //  b_jl_new_power 表中含有视在功率字段， 但是存在空字符串脏数据， 这里直接计算得出。
      val sz1: Double = math.sqrt((value.ZYGGL * value.ZYGGL) + (value.ZWGGL * value.ZWGGL)) * bjlTransformer.BL
      val sz2: Double = math.sqrt((value.AZXYG + value.BZXYG + value.CZXYG) + (value.AZXWG + value.BZXWG + value.CZXWG)) * bjlTransformer.BL
      val loadFactor: Double = math.max(sz1, sz2) / bjlTransformer.CAPACITY.toLong
      // SJSJ 2018-12-18 07:15:00
      val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      val ts: Long = sdf.parse(value.SJSJ).getTime

      if (loadFactor <= 120) {
        clearState(List(120, 130, 150, 160, 180))
      } else if (loadFactor > 180) {
        addState(List(120, 130, 150, 160, 180), bjlTransformer.MP_ID, loadFactor, ts)
      } else if (loadFactor > 160) {
        addState(List(120, 130, 150, 160), bjlTransformer.MP_ID, loadFactor, ts)
        clearState(List(180))
      } else if (loadFactor > 150) {
        addState(List(120, 130, 150), bjlTransformer.MP_ID, loadFactor, ts)
        clearState(List(160, 180))
      } else if (loadFactor > 130) {
        addState(List(120, 130), bjlTransformer.MP_ID, loadFactor, ts)
        clearState(List(150, 160, 180))
      } else if (loadFactor > 120) {
        addState(List(120), bjlTransformer.MP_ID, loadFactor, ts)
        clearState(List(130, 150, 160, 180))
      }

      // 先处理负载率在180+的情况， 若在180触发， 会将120 130 150 160的状态删除， 防止重复输出。
      // 每一条数据来时都会查询一次State， 考虑将DurTime提取出来
      // 持续时间达到触发值， 触发输出操作
      if(durTime180 > 0.5 * 60 * 60 * 1000 ) {
        val lastTimeAndDurTime180 = loadLastTimeAndDurTimeMapState.get("180")
        clearState(List(120, 130, 150, 160))
        out.collect(LongTimeLoadView(
          lastTimeAndDurTime180.MP_ID,
          lastTimeAndDurTime180.MIN_VALUE,
          lastTimeAndDurTime180.MAX_VALUE,
          "long_load_180"
        ))
      }

      if(durTime160 > 1 * 60 * 60 * 1000 ) {
        val lastTimeAndDurTime160 = loadLastTimeAndDurTimeMapState.get("160")
        clearState(List(120, 130, 150))
        out.collect(LongTimeLoadView(
          lastTimeAndDurTime160.MP_ID,
          lastTimeAndDurTime160.MIN_VALUE,
          lastTimeAndDurTime160.MAX_VALUE,
          "long_load_160"
        ))
      }

      if(durTime150 > 2 * 60 * 60 * 1000 ) {
        val lastTimeAndDurTime150 = loadLastTimeAndDurTimeMapState.get("150")
        clearState(List(120, 130))
        out.collect(LongTimeLoadView(
          lastTimeAndDurTime150.MP_ID,
          lastTimeAndDurTime150.MIN_VALUE,
          lastTimeAndDurTime150.MAX_VALUE,
          "long_load_150"
        ))
      }

      if(durTime130 > 4 * 60 * 60 * 1000 ) {
        val lastTimeAndDurTime130 = loadLastTimeAndDurTimeMapState.get("130")
        clearState(List(120))
        out.collect(LongTimeLoadView(
          lastTimeAndDurTime130.MP_ID,
          lastTimeAndDurTime130.MIN_VALUE,
          lastTimeAndDurTime130.MAX_VALUE,
          "long_load_130"
        ))
      }

      if(durTime120 > 8 * 60 * 60 * 1000 ) {
        val lastTimeAndDurTime120 = loadLastTimeAndDurTimeMapState.get("120")
        out.collect(LongTimeLoadView(
          lastTimeAndDurTime120.MP_ID,
          lastTimeAndDurTime120.MIN_VALUE,
          lastTimeAndDurTime120.MAX_VALUE,
          "long_load_120"
        ))
      }
    }

      /**
       * 根据传入的list对状态进行清空.并将标识持续时间的字段值设为0
       * @param nums 需要清空状态的编码
       */
      def clearState(nums: List[Int]): Unit = {
        for (num <- nums) {
          loadLastTimeAndDurTimeMapState.remove(num.toString)
          clearDurTime(num)
        }
      }

    /**
     * 对状态进行添加。在添加状态的时候更新持续时间的字段
     * @param nums  需要添加的负载率阶段值 120 130 150 160 180
     * @param mp_id  当前数据的主键，计量点标识
     * @param loadFactor 当前数据的负载率
     * @param ts 当前数据的时间
     */
      def addState(nums: List[Int], mp_id: String, loadFactor: Double, ts: Long): Unit = {
        for (num <- nums) {
          updateLoadLastTimeAndDurTimeMapState(num, mp_id, loadFactor, ts)
        }
      }

    /**
     * 根据传入的num在MpState中查找， 找到并计算， 得到新的durTime， ts， 然后更新MapState和持续时间标识
     * @param num  传入的是需要更新信息的MapState的key
     * @param mp_id  当前数据的计量点标识
     * @param loadFactor 当前数据的负载率
     * @param ts  当前数据的时间戳
     */
      def updateLoadLastTimeAndDurTimeMapState(num: Int, mp_id: String, loadFactor: Double, ts: Long): Unit = {
        var lastTimeAndDurTime = loadLastTimeAndDurTimeMapState.get(num.toString)
        if (lastTimeAndDurTime != null) {
          if (lastTimeAndDurTime.MAX_VALUE < loadFactor) {
            lastTimeAndDurTime.MAX_VALUE = loadFactor
          }
          if (lastTimeAndDurTime.MIN_VALUE > loadFactor) {
            lastTimeAndDurTime.MIN_VALUE = loadFactor
          }
          var lastTime = lastTimeAndDurTime.LAST_TIME
          val durTime = lastTimeAndDurTime.DUR_TIME
          lastTimeAndDurTime.DUR_TIME = (ts - lastTime) + durTime
          lastTimeAndDurTime.LAST_TIME = ts
          loadLastTimeAndDurTimeMapState.put(num.toString, lastTimeAndDurTime)
          setDurTime(num, durTime)
        } else {
          loadLastTimeAndDurTimeMapState.put(num.toString, LoadLastTimeAndDurTime(mp_id, ts, 0, loadFactor, loadFactor))
        }
      }
    }

  /**
   * 程序运行时加载之前在State中的数据，初始化当前程序的时间字段。
   */
  def initDurTime(): Unit = {
    val nums = List(120, 130, 150, 160, 180)
    for (elem <- nums) {
      val timeAndDurTime = loadLastTimeAndDurTimeMapState.get(elem.toString)
      if (timeAndDurTime != null) {
        elem match {
          case 120 => durTime120 = timeAndDurTime.DUR_TIME
          case 130 => durTime120 = timeAndDurTime.DUR_TIME
          case 150 => durTime120 = timeAndDurTime.DUR_TIME
          case 160 => durTime120 = timeAndDurTime.DUR_TIME
          case 180 => durTime120 = timeAndDurTime.DUR_TIME
        }
      }
    }
  }

  /**
   * 在更新State数据时更新时间戳字段的值
   * @param num 标识持续时间所属于的范围
   * @param durTime 持续时长
   */
  def setDurTime(num: Int, durTime: Long): Unit = {
    num match {
      case 120 => durTime120 = durTime
      case 130 => durTime130 = durTime
      case 150 => durTime150 = durTime
      case 160 => durTime160 = durTime
      case 180 => durTime180 = durTime
    }
  }

  /**
   * 用于状态清空时把时间字段设为默认值 0
   * @param num 标识持续时间所属于的范围
   */

  def clearDurTime(num: Int): Unit = {
    num match {
      case 120 => durTime120 = 0
      case 130 => durTime130 = 0
      case 150 => durTime150 = 0
      case 160 => durTime160 = 0
      case 180 => durTime180 = 0
    }
  }
}
