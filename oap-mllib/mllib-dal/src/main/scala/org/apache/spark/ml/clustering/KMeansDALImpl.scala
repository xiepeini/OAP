/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ml.clustering

import com.intel.daal.data_management.data.HomogenNumericTable
import com.intel.daal.services.DaalContext

import org.apache.spark.ml.util.{Instrumentation, OneCCL, OneDAL}
import org.apache.spark.mllib.clustering.{DistanceMeasure, KMeansModel => MLlibKMeansModel}
import org.apache.spark.mllib.linalg.{Vector => OldVector, Vectors => OldVectors}
import org.apache.spark.rdd.RDD

class KMeansDALImpl (var executorNum: Int,
  var nClusters : Int = 4,
  var maxIterations : Int = 10,
  var tolerance : Double = 1e-6,
  val distanceMeasure: String = DistanceMeasure.EUCLIDEAN,
  val centers: Array[OldVector] = null
) extends Serializable {

  def run(data: RDD[HomogenNumericTable], instr: Option[Instrumentation]) : MLlibKMeansModel = {

    instr.foreach(_.logInfo(s"Processing partitions with $executorNum executors"))

    val results = data.mapPartitions { p =>

      OneCCL.init(executorNum)

      // Assume each partition of RDD[HomogenNumericTable] has only one NumericTable
      val localData = p.next()

      val context = new DaalContext()
      localData.unpack(context)

      val initCentroids = OneDAL.makeNumericTable(centers)
      
      val cCentroids = cKMeansDALComputeWithInitCenters(
        localData.getCNumericTable,
        initCentroids.getCNumericTable,
        executorNum,
        nClusters,
        maxIterations
      )

      val ret = if (OneCCL.isRoot()) {
        assert(cCentroids != 0)

        val centerVectors = OneDAL.numericTableToVectors(OneDAL.makeNumericTable(cCentroids))
        Iterator(centerVectors)
      } else {
        Iterator.empty
      }

      OneCCL.cleanup()

      ret

    }.collect()

    // Make sure there is only one result from rank 0
    assert(results.length == 1)

    val centerVectors = results(0)

//    printNumericTable(centers)

//    Service.printNumericTable("centers", centers)

    instr.foreach(_.logInfo(s"OneDAL output centroids:\n${centerVectors.mkString("\n")}"))

    // TODO: Add cost and tolerance support in DAL
    val cost = 0.0
    val iteration = maxIterations

//    val centerVectors = OneDAL.numericTableToVectors(centers)

    val parentModel = new MLlibKMeansModel(
      centerVectors.map(OldVectors.fromML(_)),
      distanceMeasure, cost, iteration)

    parentModel
  }

  // Single entry to call KMeans DAL backend, output HomogenNumericTable representing centers
  @native private def cKMeansDALCompute(data: Long, block_num: Int,
                                        cluster_num: Int, iteration_num: Int) : Long

  // Single entry to call KMeans DAL backend with initial centers, output centers
  @native private def cKMeansDALComputeWithInitCenters(data: Long, centers: Long, block_num: Int,
                                        cluster_num: Int, iteration_num: Int) : Long


}