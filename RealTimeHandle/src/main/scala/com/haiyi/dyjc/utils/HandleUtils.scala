package com.haiyi.dyjc.utils

import com.haiyi.dyjc.entity.{BjlNewPower, BjlTransformer}

/**
 * @author Mr.Xu
 * @create 2020-09-07
 *  工具类
 */
object HandleUtils {

  /**
   * 将字符串分割转换成BjlNewPower类型
   * @param line
   */
  def toBjlNewPower(line: String): BjlNewPower = {
    val words: Array[String] = line.split("&&")
    var szgl: Double = 0
    // 脏数据处理， 空串传默认值 0
    if (words(11) != "") {
      szgl = words(11).toDouble
    }

    // 总有功功率  和   总无功功率
    val zygl: Double = words(3).toDouble
    val zwgl: Double = words(7).toDouble

    // A B C 有功功率
    val azxyg: Double = words(4).toDouble
    val bzxyg: Double = words(5).toDouble
    val czxyg: Double = words(6).toDouble

    // A B C 无功功率
    val azxwg: Double = words(8).toDouble
    val bzxwg: Double = words(9).toDouble
    val czxwg: Double = words(10).toDouble

    BjlNewPower(words(0), words(1), words(2), zygl, azxyg, bzxyg,
      czxyg,zwgl, azxwg, bzxwg, czxwg,
      szgl, words(12).toDouble, words(13).toDouble, words(14).toDouble, words(15).toDouble,
      words(16).toDouble, words(17).toDouble, words(18).toDouble, words(19))
  }

  def toBjlTransformer(line: String): BjlTransformer = {
    val words: Array[String] = line.split("&&")

    // MP_ID: 10851067583.0000000000   数据太长无法直接转换，先切分
    val mp_id_words: Array[String] = words(0).split("\\.")

    // CAPACITY: 50.0000000000  数据太长无法直接转化，先切分
    val capacity_words: Array[String] = words(27).split("\\.")
    var capacity: Double = 9999
    if(capacity_words(0) != "") {
      capacity = capacity_words(0).toDouble
    }

    // 倍率存在为null的情况， 为空时默认取 1
    var bl: Double = 1
    val temp: String = words(13)
    if(temp != "null") {
      bl = temp.toDouble
    }

    BjlTransformer(mp_id_words(0), capacity, bl)
  }


}
