/*
 * Copyright 2016 47 Degrees, LLC. <http://www.47deg.com>
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

package fetch

import cats.Eval
import cats.data.NonEmptyList

/**
  * A `DataSource` is the recipe for fetching a certain identity `I`, which yields
  * results of type `A`.
  */
trait DataSource[I, A] {
  def name: DataSourceName               = this.toString
  def identity(i: I): DataSourceIdentity = (name, i)
  def fetch(ids: NonEmptyList[I]): Eval[Map[I, A]]
}