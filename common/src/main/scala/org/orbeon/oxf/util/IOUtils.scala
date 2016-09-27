/**
 * Copyright (C) 2016 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.util

import java.io.{InputStream, OutputStream, Reader, Writer}

import scala.language.{implicitConversions, reflectiveCalls}
import scala.util.control.NonFatal

object IOUtils {

  private val CopyBufferSize = 8192

  def copyStream(in: InputStream, out: OutputStream, progress: Long ⇒ Unit = _ ⇒ ()) = {

    require(in ne null)
    require(out ne null)

    useAndClose(in) { in ⇒
      useAndClose(out) { out ⇒
        val buffer = new Array[Byte](CopyBufferSize)
        Iterator continually (in read buffer) takeWhile (_ != -1) filter (_ > 0) foreach { read ⇒
          progress(read)
          out.write(buffer, 0, read)
        }
        out.flush()
      }
    }
  }

  def copyReader(in: Reader, out: Writer, progress: Long ⇒ Unit = _ ⇒ ()) = {

    require(in ne null)
    require(out ne null)

    useAndClose(in) { in ⇒
      useAndClose(out) { out ⇒
        val buffer = new Array[Char](CopyBufferSize)
        Iterator continually (in read buffer) takeWhile (_ != -1) filter (_ > 0) foreach { read ⇒
          progress(read)
          out.write(buffer, 0, read)
        }
      }
    }
  }

  // Use a closable item and make sure an attempt to close it is done after use
  def useAndClose[T <: {def close()}, U](closable: T)(block: T ⇒ U): U =
    try block(closable)
    finally {
      if (closable ne null)
        runQuietly(closable.close())
    }

  // Run a block and swallow any exception. Use only for things like close().
  def runQuietly(block: ⇒ Unit) =
    try block
    catch {
      case NonFatal(_) ⇒ // NOP
    }
}