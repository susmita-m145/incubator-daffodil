/* Copyright (c) 2012-2015 Tresys Technology, LLC. All rights reserved.
 *
 * Developed by: Tresys Technology, LLC
 *               http://www.tresys.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal with
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimers.
 *
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimers in the
 *     documentation and/or other materials provided with the distribution.
 *
 *  3. Neither the names of Tresys Technology, nor the names of its contributors
 *     may be used to endorse or promote products derived from this Software
 *     without specific prior written permission.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
 * SOFTWARE.
 */

package edu.illinois.ncsa.daffodil.dpath

import edu.illinois.ncsa.daffodil.processors._
import edu.illinois.ncsa.daffodil.exceptions._
import edu.illinois.ncsa.daffodil.util.LocalStack
import edu.illinois.ncsa.daffodil.util.Maybe
import edu.illinois.ncsa.daffodil.util.MaybeULong
import edu.illinois.ncsa.daffodil.util.Maybe._
import edu.illinois.ncsa.daffodil.calendar.DFDLCalendar
import edu.illinois.ncsa.daffodil.equality._; object EqualityNoWarn2 { EqualitySuppressUnusedImportWarning() }
import edu.illinois.ncsa.daffodil.api.DataLocation
import edu.illinois.ncsa.daffodil.io.DataStreamCommon
import edu.illinois.ncsa.daffodil.io.DataInputStream
import edu.illinois.ncsa.daffodil.io.DataOutputStream
import edu.illinois.ncsa.daffodil.util.Coroutine
import java.math.{ BigDecimal => JBigDecimal, BigInteger => JBigInt }

/**
 * There are two modes for expression evaluation.
 */
sealed trait EvalMode

/**
 * Parsing always uses this mode, for everything.
 *
 * Unparsing uses this mode
 * for expressions that produce the values of (most? all? TBD) properties,
 * and for expressions that produce the values of variables.
 *
 * In this mode, evaluation is simple. If a child doesn't exist
 * but is needed to be traversed, an exception is thrown. If a value doesn't exist
 * an exception is thrown.
 */
case object NonBlocking extends EvalMode

/**
 * Unparsing uses this mode for outputValueCalc
 * (Also for length expressions TBD?)
 *
 * In this mode, evaluation can suspend waiting for a child
 * node to be added/appended, or for a value to be set.
 *
 * Once the situation leading to the suspension changes, evaluation
 * is retried in Regular mode.
 */
case object Blocking extends EvalMode

/**
 * expression evaluation side-effects this state block.
 *
 * For DPath expressions, all infoset content must be obtained via methods on this object so that
 * if used in a forward referencing expression the expression can block until the information
 * becomes available.
 */
case class DState() {
  import AsIntConverters._

  var isCompile = false

  var opIndex: Int = 0

  val withArray8 = new LocalStack[Array[Int]](new Array[Int](8))
  /**
   * The currentValue is used when we have a value that is not
   * associated with an element of simple type. E.g., If I have
   * the expression 5 + $x, then none of those literals, nor their
   * nor variable value, nor their sum, has an element associated with it.
   */
  private var _currentValue: AnyRef = null

  private var _mode: EvalMode = NonBlocking

  /**
   * Set to Blocking for forward-referencing expressions during unparsing.
   */
  def setMode(m: EvalMode) {
    _mode = m
  }
  def mode = _mode

  private var thisExpressionCoroutine_ : Maybe[Coroutine[AnyRef]] = Nope

  def setThisExpressionCoroutine(co: Maybe[Coroutine[AnyRef]]) {
    Assert.usage(mode eq Blocking)
    thisExpressionCoroutine_ = co
  }

  def thisExpressionCoroutine = {
    Assert.usage(mode eq Blocking)
    thisExpressionCoroutine_
  }

  private var coroutineToResumeIfBlocked_ : Maybe[Coroutine[AnyRef]] = Nope

  def setCoroutineToResumeIfBlocked(co: Maybe[Coroutine[AnyRef]]) {
    Assert.usage(mode eq Blocking)
    coroutineToResumeIfBlocked_ = co
  }

  def coroutineToResumeIfBlocked = {
    Assert.usage(mode eq Blocking)
    coroutineToResumeIfBlocked_
  }

  def resetValue() {
    _currentValue = null
  }

  def currentValue: AnyRef = {
    if (_currentValue eq null)
      withRetryIfBlocking {
        currentSimple.dataValue
      }
    else _currentValue
  }

  def setCurrentValue(v: Any) {
    Assert.invariant(!v.isInstanceOf[(Any, Any)])
    _currentValue = asAnyRef(v)
    _currentNode = null
  }

  def booleanValue: Boolean = currentValue.asInstanceOf[Boolean]

  def longValue: Long = asLong(currentValue)
  def intValue: Int = longValue.toInt
  def doubleValue: Double = asDouble(currentValue)

  def integerValue: JBigInt = asBigInt(currentValue)
  def decimalValue: JBigDecimal = asBigDecimal(currentValue)
  def stringValue: String = currentValue.asInstanceOf[String]

  def isNilled: Boolean = currentElement.isNilled

  def arrayLength: Long =
    if (currentNode.isInstanceOf[DIArray]) currentArray.length
    else {
      Assert.invariant(errorOrWarn.isDefined)
      if (currentNode.isInstanceOf[DIElement]) {
        errorOrWarn.get.SDW("The specified path to element %s is not to an array. Suggest using fn:exists instead.", currentElement.name)
      } else {
        errorOrWarn.get.SDW("The specified path is not to an array. Suggest using fn:exists instead.")
      }
      1L
    }

  def exists: Boolean = true // we're at a node, so it must exist.

  def dateValue: DFDLCalendar = currentValue.asInstanceOf[DFDLCalendar]
  def timeValue: DFDLCalendar = currentValue.asInstanceOf[DFDLCalendar]
  def dateTimeValue: DFDLCalendar = currentValue.asInstanceOf[DFDLCalendar]

  /**
   * Array index calculations (that is [expr], what XPath
   * calls 'predicate')
   */
  def index: Int = longValue.toInt

  private var _currentNode: DINode = null

  def currentNode = _currentNode
  def setCurrentNode(n: DINode) {
    _currentNode = n
    _currentValue = null
  }

  def currentSimple = {
    Assert.usage(currentNode != null)
    Assert.usage(mode != null)
    val cs = currentNode.asSimple
    //
    // If this is a computed value (for unparsing for dfdl:outputValueCalc property)
    // then if it is not yet computed, compute it.
    //
    // TODO: remove this code perhaps? This is demanded elsewhere....
    //
    //    if (mode =:= UnparseMode && !cs.hasValue && cs.runtimeData.outputValueCalcExpr.isDefined) {
    //      val expr = cs.runtimeData.outputValueCalcExpr.get
    //      val expressionValue = expr.evaluate(ustate)
    //      cs.setDataValue(expressionValue)
    //    }
    cs
  }

  def currentElement = currentNode.asInstanceOf[DIElement]
  def currentArray = currentNode.asInstanceOf[DIArray]
  def currentComplex = currentNode.asComplex

  private var _vmap: VariableMap = null

  def vmap = _vmap
  def setVMap(m: VariableMap) {
    _vmap = m
  }

  def runtimeData = {
    if (contextNode.isDefined) One(contextNode.get.erd)
    else Nope
  }

  private var _contextNode: Maybe[DINode] = Nope
  def contextNode = _contextNode
  def setContextNode(node: DINode) {
    _contextNode = One(node)
  }

  private var _bitPos1b: MaybeULong = MaybeULong.Nope
  private var _bitLimit1b: MaybeULong = MaybeULong.Nope
  private var _dataStream: Maybe[DataStreamCommon] = Nope

  def setLocationInfo(bitPos1b: Long, bitLimit1b: MaybeULong, dataStream: Maybe[DataStreamCommon]) {
    _bitPos1b = MaybeULong(bitPos1b)
    _bitLimit1b = bitLimit1b
    _dataStream = dataStream
  }

  def setLocationInfo() {
    _bitPos1b = MaybeULong.Nope
    _bitLimit1b = MaybeULong.Nope
    _dataStream = Nope
  }

  def contextLocation: Maybe[DataLocation] = {
    if (_bitPos1b.isDefined && _dataStream.isDefined) {
      val either = _dataStream.get match {
        case dis: DataInputStream => Right(dis)
        case dos: DataOutputStream => Left(dos)
      }
      One(new DataLoc(_bitPos1b.get, _bitLimit1b, either,
        (if (contextNode.isEmpty) Nope else One { contextNode.value.erd })))
    } else
      Nope
  }

  private var _savesErrorsAndWarnings: Maybe[SavesErrorsAndWarnings] = Nope
  def errorOrWarn = _savesErrorsAndWarnings
  def setErrorOrWarn(s: SavesErrorsAndWarnings) {
    _savesErrorsAndWarnings = One(s)
  }

  private var _arrayPos: Long = -1L // init to -1L so that we must set before use.
  def arrayPos = _arrayPos
  def setArrayPos(arrayPos1b: Long) {
    _arrayPos = arrayPos1b
  }

  def SDE(formatString: String, args: Any*) = {
    Assert.usage(runtimeData.isDefined)
    errorOrWarn.get.SDE(formatString, args: _*)
  }

  // These exists so we can override it in our fake DState we use when
  // checking expressions to see if they are constants. SelfMove
  // for real is a no-op, but when we're evaluating an expression to see if
  // it is a constant, the expression "." aka self, isn't constant.
  // Similarly, if you call fn:exists(....) and the contents are not gong to
  // exist at constant fold time. But we don't want fn:exists to say the result
  // is always a constant (false) because at constant folding time, hey, nothing
  // exists... this hook lets us change behavior for constant folding to throw.
  def selfMove(): Unit = {}
  def fnExists(): Unit = {}

  @inline // TODO: Performance maybe this won't allocate a closure if this is inline? If not replace with macro
  final def withRetryIfBlocking[T](body: => T): T =
    DState.withRetryIfBlocking(this)(body)
}

object DState {

  private object ToBeIgnored

  @inline
  final def withRetryIfBlocking[T](ds: DState)(body: => T): T = { // TODO: Performance maybe this won't allocate a closure if this is inline? If not replace with macro
    if (ds.mode eq NonBlocking) body
    else {
      var isDone = false
      var res: T = null.asInstanceOf[T]
      while (!isDone) {
        try {
          res = body
          isDone = true
        } catch {
          case e: InfosetRetryableException => {
            // we're to block here, and retry subsequently.
            isDone = false
            Assert.invariant(ds.thisExpressionCoroutine.isDefined)
            Assert.invariant(ds.coroutineToResumeIfBlocked.isDefined)
            ds.thisExpressionCoroutine.get.resume(ds.coroutineToResumeIfBlocked.get, ToBeIgnored)
          }
        }
      }
      res
    }
  }
}

class DStateForConstantFolding extends DState {
  private def die = throw new java.lang.IllegalStateException("No infoset at compile time.")

  override def currentSimple = currentNode.asInstanceOf[DISimple]
  override def currentElement = die
  override def currentArray = die
  override def currentComplex = die
  override def currentNode = new FakeDINode
  override def runtimeData = die
  override def vmap = die
  override def selfMove() = die
  override def fnExists() = die
  override def arrayPos = die
  override def arrayLength = die
}
