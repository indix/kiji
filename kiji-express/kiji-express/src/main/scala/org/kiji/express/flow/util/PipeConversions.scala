/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
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

package org.kiji.express.flow.util

import cascading.flow.FlowDef
import cascading.pipe.Pipe
import com.twitter.scalding.Mode
import com.twitter.scalding.RichPipe
import com.twitter.scalding.TypedPipe

import org.kiji.annotations.ApiAudience
import org.kiji.annotations.ApiStability
import org.kiji.annotations.Inheritance
import org.kiji.express.flow.ExpressResult
import org.kiji.express.flow.KijiPipe
import org.kiji.express.flow.KijiSource
import org.kiji.express.flow.TypedKijiSource

/**
 * PipeConversions contains implicit conversions necessary for KijiExpress that are not included in
 * Scalding's `Job`.
 */
@ApiAudience.Private
@ApiStability.Stable
@Inheritance.Sealed
private[express] trait PipeConversions {
  import PipeConversions._
  /**
   * Converts a Cascading Pipe to a KijiExpress KijiPipe. This method permits implicit conversions
   * from Pipe to KijiPipe.
   *
   * @param pipe to convert to a KijiPipe.
   * @return a KijiPipe wrapping the specified Pipe.
   */
  implicit def pipe2KijiPipe(pipe: Pipe): KijiPipe = new KijiPipe(pipe)

  /**
   * Converts a [[org.kiji.express.flow.KijiPipe]] to a [[cascading.pipe.Pipe]].  This
   * method permits implicit conversion from KijiPipe to Pipe.
   *
   * @param kijiPipe to convert to [[cascading.pipe.Pipe]].
   * @return Pipe instance wrapped by the input KijiPipe.
   */
  implicit def kijiPipe2Pipe(kijiPipe: KijiPipe): Pipe = kijiPipe.pipe

  /**
   * Converts a [[org.kiji.express.flow.KijiPipe]] to a [[com.twitter.scalding.RichPipe]].  This
   * method permits implicit conversion from KijiPipe to RichPipe.
   * @param kijiPipe to convert to [[com.twitter.scalding.RichPipe]].
   * @return RichPipe instance of Pipe wrapped by input KijiPipe.
   */
  implicit def kijiPipe2RichPipe(kijiPipe: KijiPipe): RichPipe = new RichPipe(kijiPipe.pipe)

  /**
   * Converts a KijiSource to a KijiExpress KijiPipe. This method permits implicit conversions
   * from Source to KijiPipe.
   *
   * We expect flowDef and mode implicits to be in scope.  This should be true in the context of a
   * Job, KijiJob, or inside the ShellRunner.
   *
   * @param source to convert to a KijiPipe
   * @return a KijiPipe read from the specified source.
   */
  implicit def source2RichPipe(
      source: KijiSource)(
      implicit flowDef: FlowDef,
      mode: Mode): KijiPipe = new KijiPipe(source.read(flowDef, mode))

  /**
   * Converts a [[TypedKijiSource]] to a TypedPipe. This method allows implicit conversion of a
   * TypedKijiSource to a TypedPipe.
   *
   * We expect flowDef and mode implicits to be in scope. This should be true in the context of a
   * Job, KijiJob, or inside the ShellRunner.
   * @param source to convert into the TypedPipe
   * @tparam T is the type parameter
   * @return
   */
  implicit def typedSource2TypedPipe[T](
      source: TypedKijiSource[T])(
      implicit flowDef: FlowDef,
      mode: Mode): TypedPipe[ExpressResult] = TypedPipe.from(source)

  /**
   * Pimp my class to allow TypedPipe to have an .thenDo function, the same as RichPipe.
   */
  implicit def typedPipeWithThenDo[T](pipe: TypedPipe[T]): AndThen[TypedPipe[T]] = new AndThen(pipe)
}

object PipeConversions {
  /**
   * Class to add andThen and thenDo to a class.
   */
  class AndThen[T](obj: T) {
    def andThen[B](then: T => B): B = then(obj)
    def thenDo[B](then: T => B): B = then(obj)
  }
}
