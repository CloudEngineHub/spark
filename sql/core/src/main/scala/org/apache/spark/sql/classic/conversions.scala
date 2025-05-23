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
package org.apache.spark.sql.classic

import scala.language.implicitConversions

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.sql
import org.apache.spark.sql.Column
import org.apache.spark.sql.catalyst.expressions.{Expression, NamedExpression}

/**
 * Conversions from sql interfaces to the Classic specific implementation.
 *
 * This class is mainly used by the implementation. It is also meant to be used by extension
 * developers.
 *
 * We provide both a trait and an object. The trait is useful in situations where an extension
 * developer needs to use these conversions in a project covering multiple Spark versions. They can
 * create a shim for these conversions, the Spark 4+ version of the shim implements this trait, and
 * shims for older versions do not.
 */
@DeveloperApi
trait ClassicConversions {
  implicit def castToImpl(session: sql.SparkSession): SparkSession =
    session.asInstanceOf[SparkSession]

  implicit def castToImpl[T](ds: sql.Dataset[T]): Dataset[T] =
    ds.asInstanceOf[Dataset[T]]

  implicit def castToImpl(rgds: sql.RelationalGroupedDataset): RelationalGroupedDataset =
    rgds.asInstanceOf[RelationalGroupedDataset]

  implicit def castToImpl[K, V](kvds: sql.KeyValueGroupedDataset[K, V])
  : KeyValueGroupedDataset[K, V] = kvds.asInstanceOf[KeyValueGroupedDataset[K, V]]

  implicit def castToImpl(context: sql.SQLContext): SQLContext = context.asInstanceOf[SQLContext]

  /**
   * Helper that makes it easy to construct a Column from an Expression.
   */
  implicit class ColumnConstructorExt(val c: Column.type) {
    def apply(e: Expression): Column = ExpressionUtils.column(e)
  }
}

@DeveloperApi
object ClassicConversions extends ClassicConversions

/**
 * Conversions from a [[Column]] to an [[Expression]].
 */
@DeveloperApi
trait ColumnConversions {
  protected def converter: ColumnNodeToExpressionConverter

  /**
   * Convert a [[Column]] into an [[Expression]].
   */
  @DeveloperApi
  def expression(column: Column): Expression = converter(column.node)

  /**
   * Wrap a [[Column]] with a [[RichColumn]] to provide the `expr` and `named` methods.
   */
  @DeveloperApi
  implicit def toRichColumn(column: Column): RichColumn = new RichColumn(column, converter)
}

/**
 * Automatic conversions from a Column to an Expression. This uses the active SparkSession for
 * parsing, and the active SQLConf for fetching configurations.
 *
 * This functionality is not part of the ClassicConversions because it is generally better to use
 * `SparkSession.toRichColumn(...)` or `SparkSession.expression(...)` directly.
 */
@DeveloperApi
object ColumnConversions extends ColumnConversions {
  override protected def converter: ColumnNodeToExpressionConverter =
    ColumnNodeToExpressionConverter
}

/**
 * Helper class that adds the `expr` and `named` methods to a Column. This can be used to reinstate
 * the pre-Spark 4 Column functionality.
 */
@DeveloperApi
class RichColumn(column: Column, converter: ColumnNodeToExpressionConverter) {
  /**
   * Returns the expression for this column.
   */
  def expr: Expression = converter(column.node)
  /**
   * Returns the expression for this column either with an existing or auto assigned name.
   */
  def named: NamedExpression = ExpressionUtils.toNamed(expr)
}
