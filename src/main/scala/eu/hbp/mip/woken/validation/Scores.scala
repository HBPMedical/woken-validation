/*
 * Copyright 2017 LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.core.validation

import org.apache.spark.sql.{ Row, SparkSession }
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.mllib.evaluation.MulticlassMetrics
import org.apache.spark.mllib.evaluation.RegressionMetrics
import spray.json.{ JsNumber, JsObject, JsString, JsValue, JsonFormat, _ }
import spray.json._
import DefaultJsonProtocol._

import eu.hbp.mip.woken.meta.VariableMetaData

/**
  * Created by Arnaud Jutzeler
  *
  *
  */
trait Scores {
  // TODO Better integration with spark!
  // Quick fix for spark 2.0.0
  System.setProperty("spark.sql.warehouse.dir", "/tmp ")
  val spark: SparkSession = SparkSession
    .builder()
    .master("local")
    .appName("Woken")
    .getOrCreate()
  def compute(outputs: List[String], labels: List[String]): Unit
}

object Scores {

  type ConfusionMatrix = scala.collection.mutable.Map[(String, String), Int]

  def enumerateLabel(targetMetaVariable: VariableMetaData): List[String] =
    targetMetaVariable.enumerations.get.keys.toList

  /**
    * Output is a list of JSON strings
    *
    * @param output
    * @param groundTruth
    * @param targetMetaVariable
    * @return
    */
  def apply(output: List[String],
            groundTruth: List[String],
            targetMetaVariable: VariableMetaData): Scores = {

    val score: Scores = targetMetaVariable.`type` match {
      case "binominal"   => new BinaryClassificationScores(enumerateLabel(targetMetaVariable))
      case "polynominal" => new ClassificationScores(enumerateLabel(targetMetaVariable))
      case _             => new RegressionScores()
    }

    score.compute(output, groundTruth)

    score
  }
}

/**
  * Wrapper around Spark MLLib's BinaryClassificationMetrics
  *
  * Metrics for binary classifiers whose output is the score (probability) of the one of the values (positive)
  *
  * TODO To be tested
  * TODO Problem: BinaryClassificationMetrics does not provide confusion matrices...
  *
  */
case class BinaryClassificationThresholdScores() extends Scores {

  var metrics: List[BinaryClassificationMetrics] = null
  var labels: Map[String, Double]                = Map()

  /**
    *
    * @param output
    * @param label
    */
  override def compute(output: List[String], label: List[String]) = {

    val data: List[(Map[String, Double], String)] = output
      .zip(label)
      .map({
        case (y, f) => (y.parseJson.convertTo[Map[String, Double]], f.parseJson.convertTo[String])
      })

    // TODO To be changed once we have the schema
    labels += (data.head._1.keys.head -> 0.0)
    labels += (data.head._1.keys.last -> 1.0)

    // Convert to dataframe
    metrics = labels.keys
      .map(l => {
        new BinaryClassificationMetrics(
          spark
            .createDataFrame(data.map({ case (x, y) => (x.get(l), if (y == l) 1.0 else 0.0) }))
            .toDF("output", "label")
            .rdd
            .map {
              case Row(output: Double, label: Double) => (output, label)
            }
        )
      })
      .toList
  }
}

/**
  * Wrapper around Spark MLLib's MulticlassMetrics
  *
  */
case class ClassificationScores(enumeration: List[String]) extends Scores {

  var metrics: MulticlassMetrics     = null
  var labelsMap: Map[String, Double] = null

  /**
    * @param output
    * @param label
    */
  override def compute(output: List[String], label: List[String]) = {

    // Convert to dataframe
    val data: List[(String, String)] = output
      .zip(label)
      .map({
        case (y, f) => (y.parseJson.convertTo[String], f.parseJson.convertTo[String])
      })

    labelsMap = enumeration.zipWithIndex.map({ case (x, i) => (x, i.toDouble) }).toMap

    val df = spark
      .createDataFrame(data.map(x => { (labelsMap.get(x._1), labelsMap.get(x._2)) }))
      .toDF("output", "label")

    val predictionAndLabels =
      df.rdd.map {
        case Row(output_index: Double, label_index: Double) => (output_index, label_index)
      }

    metrics = new MulticlassMetrics(predictionAndLabels)
  }

  def matrixJson(): JsValue = {

    val matrix = metrics.confusionMatrix
    val labels = metrics.labels

    val n = labelsMap.size
    val m = labels.length

    val array = Array.ofDim[Double](n, n)

    // Build the complete matrix
    for (i <- 0 until m) {
      for (j <- 0 until m) {
        array(labels(i).toInt)(labels(j).toInt) = matrix.apply(i, j)
      }
    }

    JsObject(
      "labels" -> labelsMap.keys.toList.toJson,
      "values" -> array.toJson
    )
  }
}

/**
  * Wrapper around Spark MLLib's MulticlassMetrics
  *
  * While waiting for usable BinaryClassificationThresholdScores...
  *
  */
class BinaryClassificationScores(enumeration: List[String])
    extends ClassificationScores(enumeration: List[String]) {

  def recall =
    metrics.confusionMatrix
      .apply(0, 0) / (metrics.confusionMatrix.apply(0, 0) + metrics.confusionMatrix.apply(0, 1))

  def precision =
    metrics.confusionMatrix
      .apply(0, 0) / (metrics.confusionMatrix.apply(0, 0) + metrics.confusionMatrix.apply(1, 0))

  def f1score =
    2.0 * recall * precision / (recall + precision)

  def falsePositiveRate =
    metrics.confusionMatrix
      .apply(1, 0) / (metrics.confusionMatrix.apply(1, 0) + metrics.confusionMatrix.apply(1, 1))
}

/**
  *
  * Wrapper around Spark MLLib's RegressionMetrics
  *
  * TODO Add residual statistics
  *
  * @param `type`
  */
case class RegressionScores(`type`: String = "regression") extends Scores {

  var metrics: RegressionMetrics = null

  override def compute(output: List[String], label: List[String]) = {

    // Convert to dataframe
    val data: List[(Double, Double)] = output
      .zip(label)
      .map({ case (y, f) => (y.parseJson.convertTo[Double], f.parseJson.convertTo[Double]) })
    val df = spark.createDataFrame(data).toDF("output", "label")

    val predictionAndLabels =
      df.rdd.map {
        case Row(prediction: Double, label: Double) => (prediction, label)
      }

    metrics = new RegressionMetrics(predictionAndLabels, false)
  }
}

object ScoresProtocol extends DefaultJsonProtocol {

  implicit object BinaryClassificationThresholdScoresJsonFormat
      extends RootJsonFormat[BinaryClassificationThresholdScores] {

    def write(s: BinaryClassificationThresholdScores): JsValue = {

      def getClosest(num: Double, listNums: List[Double]) = listNums match {
        case Nil  => Double.MaxValue
        case list => list.minBy(v => math.abs(v - num))
      }

      if (s.metrics == null) {
        return JsObject()
      }

      // TODO Put this in BinaryClassificationThresholdScores
      // Get the index for T = 0.5
      val t_0_5 = s.metrics.head
        .thresholds()
        .max()(new Ordering[Double]() {
          override def compare(x: Double, y: Double): Int =
            if (x < 0.5) {
              if (y < 0.5) Ordering[Double].compare(x, y)
              else -1
            } else if (x >= 0.5) {
              if (y > x || y < 0.5) 1 else -1
            } else 0
        })

      JsObject(
        // Accuracy for T = 0.5
        "Accuracy" -> JsNumber(0.5), // TODO

        // Precision for T = 0.5
        "Precision" -> JsNumber(
          s.metrics.head
            .precisionByThreshold()
            .filter({ case (x: Double, y: Double) => x == t_0_5 })
            .first()
            ._2
        ),
        // Recall for T = 0.5
        "Recall" -> JsNumber(
          s.metrics.head
            .recallByThreshold()
            .filter({ case (x: Double, y: Double) => x == t_0_5 })
            .first()
            ._2
        ),
        // F-Measure for T = 0.5
        "F1-score" -> JsNumber(
          s.metrics.head
            .fMeasureByThreshold()
            .filter({ case (x: Double, y: Double) => x == t_0_5 })
            .first()
            ._2
        ),
        // Area Under ROC Curve
        "Area Under ROC Curve" -> JsNumber(s.metrics.head.areaUnderPR),
        // Area Under Precision-Recall Curve
        "Area Under Precision-Recall Curve" -> JsNumber(s.metrics.head.areaUnderROC)
      )

      //TODO Add metrics by threshold...
      // Thresholds: precision.map(_._1)
      // Precision by threshold: metrics.precisionByThreshold
      // Recall by threshold: metrics.recallByThreshold
      // F1-score by threshold: metrics.fMeasureByThreshold
      // Fbeta-score by threshold: metrics.fMeasureByThreshold(beta)
      // Precision-Recall Curve: metrics.pr
      // ROC Curve: metrics.roc)
    }

    def read(value: JsValue) = value match {
      case _ => deserializationError("To be implemented")
    }
  }

  implicit object BinaryClassificationScoresJsonFormat
      extends RootJsonFormat[BinaryClassificationScores] {

    def write(s: BinaryClassificationScores): JsValue = {

      if (s.metrics == null) {
        return JsObject()
      }

      JsObject(
        "Confusion matrix"    -> s.matrixJson,
        "Accuracy"            -> JsNumber(s.metrics.accuracy),
        "Recall"              -> JsNumber(s.recall),
        "Precision"           -> JsNumber(s.precision),
        "F1-score"            -> JsNumber(s.f1score),
        "False positive rate" -> JsNumber(s.falsePositiveRate)
      )
    }

    def read(value: JsValue) = value match {
      case _ => deserializationError("To be implemented")
    }
  }

  implicit object ClassificationScoresJsonFormat extends RootJsonFormat[ClassificationScores] {

    def write(s: ClassificationScores): JsValue = {

      if (s.metrics == null) {
        return JsObject()
      }

      JsObject(
        "Confusion matrix"             -> s.matrixJson,
        "Accuracy"                     -> JsNumber(s.metrics.accuracy),
        "Weighted Recall"              -> JsNumber(s.metrics.weightedRecall),
        "Weighted Precision"           -> JsNumber(s.metrics.weightedPrecision),
        "Weighted F1-score"            -> JsNumber(s.metrics.weightedFMeasure),
        "Weighted false positive rate" -> JsNumber(s.metrics.weightedFalsePositiveRate)
      )

      //TODO Add metrics by label?
      // Precision by label: metrics.precision(l)
      // Recall by label:  metrics.recall(l)
      // False positive: metrics.falsePositiveRate(l)
      // F-measure by label: metrics.fMeasure(l)
    }

    def read(value: JsValue) = value match {
      case _ => deserializationError("To be implemented")
    }
  }

  implicit object RegressionScoresJsonFormat extends RootJsonFormat[RegressionScores] {

    def write(s: RegressionScores): JsValue = {

      if (s.metrics == null) {
        return JsObject()
      }

      JsObject(
        "MSE"                -> JsNumber(s.metrics.meanSquaredError),
        "RMSE"               -> JsNumber(s.metrics.rootMeanSquaredError),
        "R-squared"          -> JsNumber(s.metrics.r2),
        "MAE"                -> JsNumber(s.metrics.meanAbsoluteError),
        "Explained variance" -> JsNumber(s.metrics.explainedVariance)
      )
    }

    def read(value: JsValue) = value match {
      case _ => deserializationError("To be implemented")
    }
  }

  implicit object ScoresJsonFormat extends JsonFormat[Scores] {
    def write(s: Scores): JsValue =
      JsObject((s match {
        case b: BinaryClassificationScores => b.toJson
        case c: ClassificationScores       => c.toJson
        case r: RegressionScores           => r.toJson
      }).asJsObject.fields + ("type" -> JsString(s.getClass.getSimpleName)))

    def read(value: JsValue) =
      // If you need to read, you will need something in the
      // JSON that will tell you which subclass to use
      value.asJsObject.fields("type") match {
        case JsString("BinaryClassificationScores") => value.convertTo[BinaryClassificationScores]
        case JsString("ClassificationScores")       => value.convertTo[ClassificationScores]
        case JsString("RegressionScores")           => value.convertTo[RegressionScores]
        case _                                      => value.convertTo[RegressionScores]
      }
  }
}
