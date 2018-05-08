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

package io.radicalbit.nsdb.commit_log

import io.radicalbit.nsdb.commit_log.CommitLogWriterActor.CommitLogEntry

/**
  * Trait to be extended by [[CommitLogEntry]] serializer
  * Implemented by [[StandardCommitLogSerializer]]
  */
trait CommitLogSerializer {

  /**
    * Deserializes an Array[Byte] into a [[CommitLogEntry]] ADT
    *
    * @param entry byte representation
    * @return a [[CommitLogEntry]]
    */
  def deserialize(entry: Array[Byte]): CommitLogEntry

  /**
    * Serializes a [[CommitLogEntry]] ADT
    *
    * @param entry a [[CommitLogEntry]]
    * @return byte representation
    */
  def serialize(entry: CommitLogEntry): Array[Byte]

}
