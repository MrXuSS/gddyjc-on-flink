package com.haiyi.dyjc.entity

/**
 * @author Mr.Xu
 * @create 2020-09-07
 *
 *  程序中所用到的样例类
 *  */

case class BjlNewPower(
                            ROWKEY: String,
                            CLDBS: String, // 测量点标识
                            SJSJ: String, // 数据时间
                            ZYGGL: Double,// 总有功功率
                            AZXYG: Double,// A相有功功率
                            BZXYG: Double,// B相有功功率
                            CZXYG: Double,// C相有功功率
                            ZWGGL: Double,// 总无功功率
                            AZXWG: Double,// A相无功功率
                            BZXWG: Double,// B相无功功率
                            CZXWG: Double,// C相无功功率
                            SZGL: Double,// 视在功率
                            ASZGL: Double, // A相视在功率
                            BSZGL: Double,// B相视在功率
                            CSZGL: Double,// C相视在功率
                            ZGLYS: Double,// 总功率因数
                            AGLYS: Double,// A相功率因数
                            BGLYS: Double,// B相功率因数
                            CGLYS: Double,// C相功率因数
                            AREA_CODE: String // 地区编码
                         )

/*
  这里只保留需要的字段, 只需要CAPACITY字段用来计算负载率
 */
case class BjlTransformer(
                           MP_ID: String,  // 计量点标识
                           CAPACITY: Double,  // 容量， 用来计算负载率
                           BL: Double  // 倍率， 用来计算负载率
                           )

/*
  用来承载负载率计算
  （MP_ID， 负载率， ts）
 */
case class LoadResult(
                     MP_ID: String,
                     LoadFactor: Double,
                     ts: Long
                     )

/*
  用来承载长时间负载结果
 */
case class LongTimeLoadView(
                   MP_ID: String,
                   MIN_VALUE: Double,
                   MAX_VALUE: Double,
                   TYPE: String
                   )