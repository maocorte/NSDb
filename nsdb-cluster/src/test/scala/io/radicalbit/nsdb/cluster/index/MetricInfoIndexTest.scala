/*
 * Copyright 2018 Radicalbit S.r.l.
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

package io.radicalbit.nsdb.cluster.index

import java.nio.file.Files
import java.util.UUID

import io.radicalbit.nsdb.index.DirectorySupport
import org.scalatest.{FlatSpec, Matchers, OneInstancePerTest}

class MetricInfoIndexTest extends FlatSpec with Matchers with OneInstancePerTest with DirectorySupport {

  "MetricInfoIndex" should "write and read properly" in {

    lazy val directory = createMmapDirectory(Files.createTempDirectory(UUID.randomUUID().toString))

    val metricInfoIndex = new MetricInfoIndex(directory)

    val writer = metricInfoIndex.getWriter

    (0 to 100).foreach { i =>
      val testData = MetricInfo(s"metric_$i", i)
      metricInfoIndex.write(testData)(writer)
    }
    writer.close()
    metricInfoIndex.refresh()

    val result = metricInfoIndex.query(metricInfoIndex._keyField, "metric_*", Seq.empty, 100)(identity)

    result.size shouldBe 100

    val firstMetricInfo = metricInfoIndex.getMetricInfo("metric_0")

    firstMetricInfo shouldBe Some(
      MetricInfo(s"metric_0", 0)
    )
  }

  "MetricInfoIndex" should "write and delete properly" in {

    lazy val directory = createMmapDirectory(Files.createTempDirectory(UUID.randomUUID().toString))

    val metricInfoIndex = new MetricInfoIndex(directory)

    val writer = metricInfoIndex.getWriter

    (0 to 100).foreach { i =>
      val testData = MetricInfo(s"metric_$i", i)
      metricInfoIndex.write(testData)(writer)
    }
    writer.close()
    metricInfoIndex.refresh()

    val firstExpected = MetricInfo(s"metric_0", 0)
    metricInfoIndex.getMetricInfo("metric_0") shouldBe Some(
      firstExpected
    )

    val deleteWriter = metricInfoIndex.getWriter

    metricInfoIndex.delete(firstExpected)(deleteWriter)

    deleteWriter.close()

    metricInfoIndex.refresh()

    metricInfoIndex.getMetricInfo("metric_0") shouldBe None
  }
}
