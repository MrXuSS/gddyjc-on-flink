package com.haiyi.dyjc.entity

import java.io.{BufferedReader, FileReader}

import com.haiyi.dyjc.utils.HandleUtils
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.source.{RichParallelSourceFunction, SourceFunction}

/**
 * @author Mr.Xu
 * @description: 数据源，读取档案表和实时功率表; 档案表30s读取一遍并将isFinished标识为true，实时功率表实时监测isFinished， 若为true开始读取。
 * @create 2020-09-07 19:55
 */
object Sources {

  // 标识档案表是否加载完
  var isFinished: Boolean = _

  class BjlNewPowerSource extends RichParallelSourceFunction[BjlNewPower]{

    var fileReader: FileReader = _
    var bufferedReader: BufferedReader = _

    override def run(ctx: SourceFunction.SourceContext[BjlNewPower]): Unit = {
      while (!isFinished) {
        Thread.sleep(5000)
      }
      fileReader = new FileReader("D:\\Work\\电压监测测试Flink\\部分数据\\b_jl_new_power.txt")
      bufferedReader = new BufferedReader(fileReader)
      while (true) {
        if (isFinished) {
          var line: String = bufferedReader.readLine()
          while (line != null) {
            val bjlNewPower: BjlNewPower = HandleUtils.toBjlNewPower(line)
            line = bufferedReader.readLine()
            ctx.collect(bjlNewPower)
          }
        }
      }
    }

    override def cancel(): Unit = {
      bufferedReader.close()
      fileReader.close()
    }
  }

  class BjlTransformerSource extends RichParallelSourceFunction[BjlTransformer]{

    var fileReader: FileReader = _
    var bufferedReader: BufferedReader = _

    override def run(ctx: SourceFunction.SourceContext[BjlTransformer]): Unit = {
      try {
        fileReader = new FileReader("D:\\Work\\电压监测测试Flink\\部分数据\\b_jl_transformer.txt")
        bufferedReader = new BufferedReader(fileReader)
        var line: String = bufferedReader.readLine()
        while (line != null) {
          val transformer: BjlTransformer = HandleUtils.toBjlTransformer(line)
          line = bufferedReader.readLine()
          ctx.collect(transformer)
        }
        isFinished = true
        Thread.sleep(30000)
        isFinished = false
        this.run(ctx)
      }
    }

    override def cancel(): Unit = {
      bufferedReader.close()
      fileReader.close()
    }
  }

}
