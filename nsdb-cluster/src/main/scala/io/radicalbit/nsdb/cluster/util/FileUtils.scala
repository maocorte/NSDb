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

package io.radicalbit.nsdb.cluster.util

import java.io._
import java.nio.file.Path
import java.util.zip.{ZipEntry, ZipFile}

import scala.collection.JavaConverters._

/**
  * Contains utility methods to handle files.
  */
object FileUtils {

  private class DirectoryFilter extends FileFilter {
    override def accept(pathname: File): Boolean = pathname.isDirectory
  }

  private val BUFFER_SIZE = 4096
  private val buffer      = new Array[Byte](BUFFER_SIZE)

  /**
    * @param path a directory.
    * @return all the first level sub directories of the given directory.
    */
  def getSubDirs(path: File): Array[File] = path.listFiles(new DirectoryFilter)

  /**
    * @param path a directory.
    * @return all the first level sub directories of the given directory.
    */
  def getSubDirs(path: Path): Array[File] = path.toFile.listFiles(new DirectoryFilter)

  /**
    * @param path a string path.
    * @return all the first level sub directories of the given directory.
    */
  def getSubDirs(path: String): List[File] =
    Option(new File(path).listFiles(new DirectoryFilter)).map(_.toList).getOrElse(List.empty)

  /**
    * Unzip a file into a target folder.
    * @param source the input zip path.
    * @param targetFolder the folder to unzip the input file into.
    */
  def unzip(source: String, targetFolder: String): AnyVal = {
    if (new File(source).exists) {
      val zipFile = new ZipFile(source)
      unzipAllFile(zipFile, zipFile.entries.asScala.toList, new File(targetFolder))
    }
  }

  /**
    * unzip all the entries of a zip file into a target folder.
    * @param zipFile the input zip file.
    * @param entries the entries to be extracted.
    * @param targetFolder the folder to unzip the input file into.
    */
  private def unzipAllFile(zipFile: ZipFile, entries: Seq[ZipEntry], targetFolder: File): Boolean = {
    entries match {
      case entry :: tailEntries =>
        if (entry.isDirectory)
          new File(targetFolder, entry.getName).mkdirs
        else
          saveFile(zipFile.getInputStream(entry), new FileOutputStream(new File(targetFolder, entry.getName)))

        unzipAllFile(zipFile, tailEntries, targetFolder)

      case _ =>
        true
    }
  }

  /**
    * Save a file from an input stream to an output stream.
    * @param fis the input stream.
    * @param fos the output stream.
    */
  private def saveFile(fis: InputStream, fos: OutputStream) = {

    def bufferReader(fis: InputStream)(buffer: Array[Byte]) = (fis.read(buffer), buffer)

    def writeToFile(reader: Array[Byte] => (Int, Array[Byte]), fos: OutputStream): Boolean = {
      val (length, data) = reader(buffer)
      if (length >= 0) {
        fos.write(data, 0, length)
        writeToFile(reader, fos)
      } else
        true
    }

    try {
      writeToFile(bufferReader(fis) _, fos)
    } finally {
      fis.close()
      fos.close()
    }
  }
}
