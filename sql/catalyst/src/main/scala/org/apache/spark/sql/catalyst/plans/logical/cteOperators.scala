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

package org.apache.spark.sql.catalyst.plans.logical

import org.apache.spark.sql.catalyst.analysis.{MultiInstanceRelation, UnresolvedSubqueryColumnAliases}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.trees.TreePattern._
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.internal.SQLConf

/**
 * The logical node for CTE recursion, that contains a initial (anchor) and a recursion describing
 * term, that contains an [[UnionLoopRef]] node in its recursive child.
 * The node is very similar to [[Union]] because the initial and "generated" children are union-ed
 * and it is also similar to a loop because the recursion continues until the last generated child
 * is not empty. Union in a recursive CTE gets resolved into UnionLoop.
 *
 * @param id The id of the loop, inherited from [[CTERelationDef]] within which the Union lived.
 * @param anchor The plan of the initial element of the loop.
 * @param recursion The plan that describes the recursion with an [[UnionLoopRef]] node.
 * @param outputAttrIds The ids of UnionLoop's output attributes.
 * @param limit An optional limit that can be pushed down to the node to stop the loop earlier.
 * @param maxDepth Maximal number of iterations before we report an error.
 */
case class UnionLoop(
    id: Long,
    anchor: LogicalPlan,
    recursion: LogicalPlan,
    outputAttrIds: Seq[ExprId],
    limit: Option[Int] = None,
    maxDepth: Option[Int] = None) extends UnionBase {
  override def children: Seq[LogicalPlan] = Seq(anchor, recursion)

  override protected def withNewChildrenInternal(newChildren: IndexedSeq[LogicalPlan]): UnionLoop =
    copy(anchor = newChildren(0), recursion = newChildren(1))

  override protected def computeOutput(): Seq[Attribute] =
    Union.mergeChildOutputs(children.map(_.output)).zip(outputAttrIds).map { case (x, id) =>
      x.withExprId(id)
    }

  override def argString(maxFields: Int): String = {
    id.toString + limit.map(", " + _.toString).getOrElse("") +
      maxDepth.map(", " + _.toString).getOrElse("")
  }

  override lazy val resolved: Boolean = {
    // allChildrenCompatible needs to be evaluated after childrenResolved
    childrenResolved && allChildrenCompatible
  }
}

/**
 * The recursive reference in the recursive child of an [[UnionLoop]] node. CTERelationRef in a
 * recursive CTE gets resolved into UnionLoopRef.
 *
 * @param loopId The id of the loop, inherited from [[CTERelationRef]] which got resolved into this
 *               UnionLoopRef.
 * @param output The output attributes of this recursive reference.
 * @param accumulated If false the reference stands for the result of the previous iteration.
 *                    If it is true then it stands for the union of all previous iteration
 *                    results.
 */
case class UnionLoopRef(
    loopId: Long,
    override val output: Seq[Attribute],
    accumulated: Boolean) extends LeafNode with MultiInstanceRelation {
  override def newInstance(): LogicalPlan = copy(output = output.map(_.newInstance()))

  override def computeStats(): Statistics = Statistics(SQLConf.get.defaultSizeInBytes)

  override def verboseStringWithOperatorId(): String = {
    s"""
       |$formattedNodeName
       |Loop id: $loopId
       |${QueryPlan.generateFieldString("Output", output)}
       |Accumulated: $accumulated
       |""".stripMargin
  }
}

/**
 * A wrapper for CTE definition plan with a unique ID.
 * @param child The CTE definition query plan.
 * @param id    The unique ID for this CTE definition.
 * @param originalPlanWithPredicates The original query plan before predicate pushdown and the
 *                                   predicates that have been pushed down into `child`. This is
 *                                   a temporary field used by optimization rules for CTE predicate
 *                                   pushdown to help ensure rule idempotency.
 * @param underSubquery If true, it means we don't need to add a shuffle for this CTE relation as
 *                      subquery reuse will be applied to reuse CTE relation output.
 * @param maxDepth The maximal depth of a recursion in a recursive CTE.
 */
case class CTERelationDef(
    child: LogicalPlan,
    id: Long = CTERelationDef.newId,
    originalPlanWithPredicates: Option[(LogicalPlan, Seq[Expression])] = None,
    underSubquery: Boolean = false,
    maxDepth: Option[Int] = None) extends UnaryNode {

  final override val nodePatterns: Seq[TreePattern] = Seq(CTE)

  override def maxRows: Option[Long] = if (conf.getConf(SQLConf.CTE_RELATION_DEF_MAX_ROWS)) {
    child.maxRows
  } else {
    None
  }

  override protected def withNewChildInternal(newChild: LogicalPlan): LogicalPlan =
    copy(child = newChild)

  override def output: Seq[Attribute] = if (resolved) child.output else Nil

  lazy val hasSelfReferenceAsCTERef: Boolean = child.collectFirstWithSubqueries {
    case CTERelationRef(this.id, _, _, _, _, true, _) => true
  }.getOrElse(false)
  lazy val hasSelfReferenceInAnchor: Boolean = {
    val unionNode: Option[Union] = child match {
      case SubqueryAlias(_, union: Union) =>
        Some(union)
      case SubqueryAlias(_, UnresolvedSubqueryColumnAliases(_, union: Union)) =>
        Some(union)
      case SubqueryAlias(_, WithCTE(union: Union, _)) =>
        Some(union)
      case SubqueryAlias(_, UnresolvedSubqueryColumnAliases(_, WithCTE(union: Union, _))) =>
        Some(union)
      case _ => None
    }
    if (unionNode.isDefined) {
      unionNode.get.children.head.collectFirstWithSubqueries {
        case CTERelationRef(this.id, _, _, _, _, true, _) => true
      }.getOrElse(false)
    } else {
      false
    }
  }
  lazy val hasSelfReferenceAsUnionLoopRef: Boolean = child.collectFirstWithSubqueries {
    case UnionLoopRef(this.id, _, _) => true
  }.getOrElse(false)
}

object CTERelationDef {
  private[sql] val curId = new java.util.concurrent.atomic.AtomicLong()
  def newId: Long = curId.getAndIncrement()
}

/**
 * Represents the relation of a CTE reference.
 * @param cteId                The ID of the corresponding CTE definition.
 * @param _resolved            Whether this reference is resolved.
 * @param output               The output attributes of this CTE reference, which can be different
 *                             from the output of its corresponding CTE definition after attribute
 *                             de-duplication.
 * @param statsOpt             The optional statistics inferred from the corresponding CTE
 *                             definition.
 * @param recursive            If this is a recursive reference.
 */
case class CTERelationRef(
    cteId: Long,
    _resolved: Boolean,
    override val output: Seq[Attribute],
    override val isStreaming: Boolean,
    statsOpt: Option[Statistics] = None,
    recursive: Boolean = false,
    override val maxRows: Option[Long] = None) extends LeafNode with MultiInstanceRelation {

  final override val nodePatterns: Seq[TreePattern] = Seq(CTE)

  override lazy val resolved: Boolean = _resolved

  override def newInstance(): LogicalPlan = {
    // CTERelationRef inherits the output attributes from a query, which may contain duplicated
    // attributes, for queries like `SELECT a, a FROM t`. It's important to keep the duplicated
    // attributes to have the same id in the new instance, as column resolution allows more than one
    // matching attributes if their ids are the same.
    // For example, `Project('a, CTERelationRef(a#1, a#1))` can be resolved properly as the matching
    // attributes `a` have the same id, but `Project('a, CTERelationRef(a#2, a#3))` can't be
    // resolved.
    val oldAttrToNewAttr = AttributeMap(output.zip(output.map(_.newInstance())))
    copy(output = output.map(attr => oldAttrToNewAttr(attr)))
  }

  def withNewStats(statsOpt: Option[Statistics]): CTERelationRef = copy(statsOpt = statsOpt)

  override def computeStats(): Statistics = statsOpt.getOrElse(Statistics(conf.defaultSizeInBytes))
}

/**
 * The resolved version of [[UnresolvedWith]] with CTE referrences linked to CTE definitions
 * through unique IDs instead of relation aliases.
 *
 * @param plan    The query plan.
 * @param cteDefs The CTE definitions.
 */
case class WithCTE(plan: LogicalPlan, cteDefs: Seq[CTERelationDef]) extends LogicalPlan {

  final override val nodePatterns: Seq[TreePattern] = Seq(CTE)

  override def output: Seq[Attribute] = plan.output

  override def children: Seq[LogicalPlan] = cteDefs :+ plan

  override protected def withNewChildrenInternal(
      newChildren: IndexedSeq[LogicalPlan]): LogicalPlan = {
    copy(plan = newChildren.last, cteDefs = newChildren.init.asInstanceOf[Seq[CTERelationDef]])
  }

  def withNewPlan(newPlan: LogicalPlan): WithCTE = {
    withNewChildren(children.init :+ newPlan).asInstanceOf[WithCTE]
  }

  override def maxRows: Option[Long] = plan.maxRows

  override def maxRowsPerPartition: Option[Long] = plan.maxRowsPerPartition
}

/**
 * The logical node which is able to place the `WithCTE` node on its children.
 */
trait CTEInChildren extends LogicalPlan {
  def withCTEDefs(cteDefs: Seq[CTERelationDef]): LogicalPlan = {
    withNewChildren(children.map(WithCTE(_, cteDefs)))
  }
}

/**
 * A container for holding named common table expressions (CTEs) and a query plan.
 * This operator will be removed during analysis and the relations will be substituted into child.
 *
 * @param child The final query of this CTE.
 * @param cteRelations A sequence of pair (alias, the CTE definition) that this CTE defined
 *                     Each CTE can see the base tables and the previously defined CTEs only.
 * @param allowRecursion A boolean flag if recursion is allowed.
 */
case class UnresolvedWith(
    child: LogicalPlan,
    cteRelations: Seq[(String, SubqueryAlias, Option[Int])],
    allowRecursion: Boolean = false) extends UnaryNode {
  final override val nodePatterns: Seq[TreePattern] = Seq(UNRESOLVED_WITH)

  override def output: Seq[Attribute] = child.output

  override def simpleString(maxFields: Int): String = {
    val cteAliases = truncatedString(cteRelations.map(_._1), "[", ", ", "]", maxFields)
    s"CTE $cteAliases"
  }

  override def innerChildren: Seq[LogicalPlan] = cteRelations.map(_._2)

  override protected def withNewChildInternal(newChild: LogicalPlan): UnresolvedWith =
    copy(child = newChild)
}
