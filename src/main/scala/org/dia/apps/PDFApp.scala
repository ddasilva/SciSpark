/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dia.apps

import scala.collection.mutable.{ArrayBuffer, HashMap, Set}

import org.nd4j.linalg.factory.Nd4j

import org.apache.spark.mllib.clustering.KMeans
import org.apache.spark.mllib.linalg.{DenseVector, Vector}

import org.dia.algorithms.pdfclustering._
import org.dia.core.{SciSparkContext, SciTensor}
import org.dia.tensors.{AbstractTensor, Nd4jTensor}
/**
 *
 */
object PDFApp extends App {

  val masterURL = "local[2]"
  val sc = new SciSparkContext(masterURL, "PDF clustering")
  /**
   * User inputs describing the parameters of the dataset.
   */
  val startYear = 2001
  val endYear = 2002
  val numYears = endYear - startYear + 1
  val numJanuaryDays = 2
  val totNumDays = numYears * numJanuaryDays
  val numLats = 2
  val numLongs = 2
  val numHourly = 2

  /**
   * Compute random lat's,lon's together with random precipitation(lat,lon).
   */
  val randTensors = ArrayBuffer.empty[SciTensor]
  var idxTensor = 0
  val years = startYear to endYear
  val daysOfJan = 1 to numJanuaryDays
  val randLat: AbstractTensor = new Nd4jTensor(Nd4j.rand(Array(numLats)))
  val randLon: AbstractTensor = new Nd4jTensor(Nd4j.rand(Array(numLongs)))
  while (idxTensor < totNumDays) {
    val randPrec: AbstractTensor = new Nd4jTensor(Nd4j.rand(Array(numHourly, numLats, numLongs)))
    val randMap = HashMap("lat" -> randLat, "lon" -> randLon, "prec" -> randPrec)
    val randSciTensor = new SciTensor(randMap)
    val year = years(idxTensor / numJanuaryDays)
    val dayOfJan = daysOfJan(idxTensor % numJanuaryDays)
    randSciTensor.insertDictionary(("YEAR", year.toString()))
    randSciTensor.insertDictionary(("DAYOFJAN", dayOfJan.toString()))
    randTensors += randSciTensor
    idxTensor += 1
  }

  /**
   * Distribute it out to the Spark executors.
   */
  val rddStart = sc.sparkContext.parallelize(randTensors)

  /**
   * Average precipitation values over the hours for every day.
   */
  val dayAvgs = rddStart.map({ t =>
    /**
     * Averages the SciTensor along a dimension.
     *
     * @todo abstract this out into AbstractTensor
     * @todo generalize "prec" field
     * @todo can we get rid of explicit ': AbstractTensor's here?
     * also need to maybe slightly modify AbstractTensor interface
     * to achieve that.
     */
    def avgAlongDim(st: SciTensor): AbstractTensor = {
      val prec = st.variables("prec")
      val dummy: AbstractTensor = prec((0, 0))
      val avg: AbstractTensor = dummy.zeros(Seq(numLats, numLongs): _*)
      var hour = 0
      var lats = 0
      var longs = 0
      while (hour < numHourly) {
        lats = 0
        while (lats < numLats) {
          longs = 0
          while (longs < numLongs) {
            val curSum = avg(lats, longs)
            val newSum = curSum + prec(hour, lats, longs)
            avg.put(newSum, Seq(lats, longs): _*)
            longs += 1
          }
          lats += 1
        }
        hour += 1
      }
      avg.div(numHourly)
    }
    val precAvg = avgAlongDim(t)
    /** overwrites prec with precAvg but keeps lat,lon tensors */
    t.insertVar("prec", precAvg)
    t
  })

  /**
   * Group by the day of January,
   * as a preparation for the next step.
   */
  val groupedByJanDay = dayAvgs.groupBy({ t =>
    t.metaData("DAYOFJAN")
  })

  /**
   * Average over the years for every fixed day of January.
   */
  val subAvgByDay = groupedByJanDay.flatMap({
    case (day, ts) =>
      def compAvg(tensors: Iterable[SciTensor]): AbstractTensor = {
        val precs = tensors.map(_.variables("prec"))
        val sumPrecs = precs.reduce(_ + _)
        sumPrecs.div(numYears)
      }
      val avgPrec = compAvg(ts)
      ts.map({ t =>
        t.insertVar("prec", t.variables("prec") - avgPrec)
        t
      })
  })

  /**
   * Rearrange the RDD by going from
   * RDD[SciTensor] with SciTensor = (lat,lon,prec(lat,lon))
   * to
   * RDD[((lat,lon),prec(lat,lon))]
   * as a preparation for subsequent steps below.
   */
  val precPerLatLon = subAvgByDay.flatMap({ t =>
    val vars = t.variables
    val lats = vars("lat")
    val longs = vars("lon")
    val prec = vars("prec")
    /** ( (lat,lon), time series ) */
    var idx = 0
    var jdx = 0
    var precPerLatLon: Set[((Double, Double), Double)] = Set()
    while (idx < numLats) {
      jdx = 0
      while (jdx < numLongs) {
        val elem = ((lats(idx, 0), longs(jdx, 0)), prec(idx, jdx))
        precPerLatLon += elem
        jdx += 1
      }
      idx += 1
    }
    precPerLatLon
  })

  /**
   * Compute the bin size.
   */
  val minPrec = precPerLatLon.map(_._2).fold(Double.MaxValue)(math.min)
  val maxPrec = precPerLatLon.map(_._2).fold(Double.MinValue)(math.max)
  val range = maxPrec - minPrec
  val numBins = 10
  val binSize = range / numBins

  /**
   * Do the binning.
   */
  val timeSeriesPerLatLon = precPerLatLon.groupByKey
  val binned = timeSeriesPerLatLon.map({
    case (pos, precs) =>
      val binCounts = new Array[Double](numBins)
      def getBinNo(prec: Double): Option[Int] = {
        var idx = 0
        while (idx < numBins) {
          if (minPrec + idx * binSize <= prec && prec <= minPrec + (idx + 1) * binSize) {
            return Some(idx)
          }
          idx += 1
        }
        return None
      }
      precs.foreach({ prec =>
        getBinNo(prec) match {
          case Some(idx) => binCounts(idx) += 1
          case _ => ()
        }
      })
      /**
       * @todo add log10. but be aware: it leads to -Infinity's which spoils the
       * result of the clustering. think about how to resolve that.
       */
      val normalizedBinCounts = binCounts.map(bc => bc / totNumDays)
      /** make bin counts an mllib vector so we can use pre-written clustering */
      val vec: Vector = new DenseVector(normalizedBinCounts)
      (pos, vec)
  })

  /**
   * Now we can actually start the clustering.
   */
  val numClusters = 3
  val numIterations = 10
  val binnedWithoutPos = binned.map(_._2).cache()
  val clusteringOut = KMeans.train(binnedWithoutPos, numClusters, numIterations)
  /** cluster centers */
  val clusterCenters = clusteringOut.clusterCenters
  /** (lat,lon) with cluster it belongs to */
  val clusters = binned.map({
    case (pos, bcs) =>
      val clusterId = clusteringOut.predict(bcs)
      (pos, clusterId)
  })
}

