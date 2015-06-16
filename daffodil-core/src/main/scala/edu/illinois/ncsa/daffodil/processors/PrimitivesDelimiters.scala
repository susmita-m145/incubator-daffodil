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

package edu.illinois.ncsa.daffodil.processors

import java.nio.ByteBuffer
import scala.collection.mutable.Queue
import edu.illinois.ncsa.daffodil.grammar.Terminal
import edu.illinois.ncsa.daffodil.grammar.Gram
import scala.util.parsing.input.Reader
import edu.illinois.ncsa.daffodil.Implicits._
import edu.illinois.ncsa.daffodil.compiler._
import edu.illinois.ncsa.daffodil.dsom._
import edu.illinois.ncsa.daffodil.exceptions.Assert
import edu.illinois.ncsa.daffodil.exceptions.ThrowsSDE
import edu.illinois.ncsa.daffodil.exceptions.UnsuppressableException
import edu.illinois.ncsa.daffodil.grammar.Gram
import edu.illinois.ncsa.daffodil.grammar.Terminal
import edu.illinois.ncsa.daffodil.processors.{ Parser => DaffodilParser }
import edu.illinois.ncsa.daffodil.processors.unparsers.{ Unparser => DaffodilUnparser }
import edu.illinois.ncsa.daffodil.processors.dfa.TextParser
import edu.illinois.ncsa.daffodil.processors.parsers.DelimiterTextParser
import edu.illinois.ncsa.daffodil.schema.annotation.props.gen.EscapeKind
import edu.illinois.ncsa.daffodil.schema.annotation.props.gen.YesNo
import edu.illinois.ncsa.daffodil.util.Maybe
import edu.illinois.ncsa.daffodil.util.Maybe._
import edu.illinois.ncsa.daffodil.processors.parsers.DelimiterTextType
import edu.illinois.ncsa.daffodil.processors.unparsers.DelimiterTextUnparser
import java.nio.charset.StandardCharsets

trait ComputesValueFoundInstead {
  def computeValueFoundInsteadOfDelimiter(state: PState, maxDelimiterLength: Int): String = {
    val ei = state.getContext().encodingInfo
    val cs = if (ei.isKnownEncoding) ei.knownEncodingCharset.charset else StandardCharsets.UTF_8 // guess utf8
    var dis = state.dataInputStream
    val maybeFoundInstead = dis.getSomeString(maxDelimiterLength)
    val foundInstead = maybeFoundInstead.getOrElse("")
    foundInstead
  }
}

abstract class Text(es: Term, e: Term, guard: Boolean) extends DelimParserBase(es, guard) {

  lazy val oes = {
    val oes = e.optionEscapeScheme
    oes.foreach { es =>
      e.schemaDefinitionUnless(es.isKnownEscapeCharacter != Some(false), "Runtime expressions for escapeCharacters are not supported.")
      e.schemaDefinitionUnless(es.isKnownEscapeEscapeCharacter != Some(false), "Runtime expressions for escapeEscapeCharacters are not supported.")
    }
    oes
  }

  lazy val eName = e.toString()

  lazy val positionalInfo = {
    if (e.isDirectChildOfSequence) {
      e.nearestEnclosingSequence match {
        case Some(es) => {
          val pos = e.positionInNearestEnclosingSequence - 1
          if (es.hasPrefixSep) {
            if (e.hasPriorRequiredSiblings) {
              val prior: Term = es.groupMembers(pos - 1)
              "after " + prior.prettyName + " and before " + eName
            } else "before " + eName
          } else if (es.hasInfixSep)
            if (e.hasPriorRequiredSiblings && e.hasLaterRequiredSiblings) {
              val prior: Term = es.groupMembers(pos - 1)

              "after " + prior.prettyName + " and before " + eName
            } else if (e.hasPriorRequiredSiblings) {
              val prior: Term = es.groupMembers(pos - 1)
              "after " + prior.prettyName + " and before " + eName
            } else if (e.hasLaterRequiredSiblings) {
              val later: Term = es.groupMembers(pos + 1)
              "before " + later.prettyName
            } else { "" }
          else if (es.hasPostfixSep)
            if (e.hasPriorRequiredSiblings && e.hasLaterRequiredSiblings) {
              val later: Term = es.groupMembers(pos + 1)

              "after " + eName + " and before " + later.prettyName
            } else if (e.hasPriorRequiredSiblings) {
              val prior: Term = es.groupMembers(pos - 1)
              "after " + prior.prettyName + " and before " + eName
            } else if (e.hasLaterRequiredSiblings) {
              val later: Term = es.groupMembers(pos + 1)
              "before " + later.prettyName
            } else { "" }
          else
            ""
        }
        case None => ""
      }
    } else {
      ""
    }
  }

  def getMatchedDelimiterInfo(remoteDelimRegex: Set[(String, String)], foundDelimiter: String,
    delimiters: List[(List[String], String, String)]) = {
    val matchedDelim = remoteDelimRegex.find {
      case (delimRegex, _) => {
        foundDelimiter.matches("(?s)^(" + delimRegex + ")$")
      }
    } match {
      case Some((_, theValue)) => theValue
      case None => Assert.impossibleCase()
    }

    val (remoteDelimValue, remoteElemName, remoteElemPath, _) =
      {
        val findResult = delimiters.map {
          case (delimValueList, elemName, elemPath) => {
            delimValueList.find(delim => delim == matchedDelim) match {
              case Some(d) => (d, elemName, elemPath, true)
              case None => (delimValueList.mkString(","), elemName, elemPath, false)
            }
          }
        }.toSet.filter { x => x._4 == true }

        if (findResult.size == 0) Assert.impossibleCase()
        findResult.head
      }
    (remoteDelimValue, remoteElemName, remoteElemPath)
  }

  def getMatchedDelimiterInfo(originalDelimRep: String,
    delimiters: List[(List[String], String, String)]) = {

    val (remoteDelimValue, remoteElemName, remoteElemPath, _) =
      {
        val findResult = delimiters.map {
          case (delimValueList, elemName, elemPath) => {
            val res = delimValueList.find(delim => delim == originalDelimRep) match {
              case Some(d) => (d, elemName, elemPath, true)
              case None => (delimValueList.mkString(","), elemName, elemPath, false)
            }
            res
          }
        }.toSet.filter { x => x._4 == true }

        if (findResult.size == 0) Assert.impossibleCase()
        findResult.head
      }
    (remoteDelimValue, remoteElemName, remoteElemPath)
  }

}

// NOTE: LiteralNil still uses this as it can only be Static/Constant
//
abstract class StaticText(delim: String, e: Term, eb: Term, kindString: String, guard: Boolean = true)
  extends Text(e, eb, guard) with ComputesValueFoundInstead {

  Assert.invariant(delim != "") // shouldn't be here at all in this case.

  e.schemaDefinitionWarningUnless(e.ignoreCase == YesNo.No, "Property ignoreCase='yes' not supported.")

  lazy val textParser = new TextParser(e.termRuntimeData)
}

abstract class DelimiterText(kindString: String, delimExpr: CompiledExpression, e: Term, eb: Term, guard: Boolean = true)
  extends Text(e, eb, guard) with ComputesValueFoundInstead {

  e.schemaDefinitionWarningUnless(e.ignoreCase == YesNo.No, "Property ignoreCase='yes' not supported.")

  lazy val textParser = new TextParser(e.termRuntimeData)

  def delimiterType: DelimiterTextType.Type

  override lazy val parser: DaffodilParser = new DelimiterTextParser(e.termRuntimeData, delimExpr, kindString, textParser, positionalInfo, delimiterType)
  override lazy val unparser: DaffodilUnparser = new DelimiterTextUnparser(e.termRuntimeData, delimExpr, delimiterType)
}

case class Initiator(e: Term) extends DelimiterText("Init", e.initiator, e, e) {
  Assert.invariant(e.hasInitiator)
  val delimiterType: DelimiterTextType.Type = DelimiterTextType.Initiator
}
case class Separator(s: Sequence, t: Term) extends DelimiterText("Sep", s.separator, s, t) {
  Assert.invariant(s.hasSeparator)
  val delimiterType: DelimiterTextType.Type = DelimiterTextType.Separator
}
case class Terminator(e: Term) extends DelimiterText("Term", e.terminator, e, e) {
  Assert.invariant(e.hasTerminator)
  val delimiterType: DelimiterTextType.Type = DelimiterTextType.Terminator
}

abstract class DelimParserBase(e: Term, guard: Boolean) extends Terminal(e, guard) {
  override def toString = "DelimParserBase[" + name + "]"

  private def isPrefixOf(possiblePrefix: String, string: String): Boolean = {
    string.startsWith(possiblePrefix)
  }

  private def checkPadCharDistinctness(
    padChar: Maybe[String],
    escChar: Maybe[String],
    escEscChar: Maybe[String],
    escBlockStart: Maybe[String],
    escBlockEnd: Maybe[String],
    terminatingMarkup: Seq[String],
    context: ThrowsSDE): Unit = {

    padChar.foreach(pc => {
      escChar.foreach(ec => {
        if (pc == ec || isPrefixOf(pc, ec))
          context.SDE("The pad character (%s) cannot be the same as (or a prefix of) the escape character.", pc)
      })
      escEscChar.foreach(eec => {
        if (pc == eec || isPrefixOf(pc, eec))
          context.SDE("The pad character (%s) cannot be the same as (or a prefix of) the escape escape character.", pc)
      })
      escBlockStart.foreach(ebs => {
        if (pc == ebs || isPrefixOf(pc, ebs))
          context.SDE("The pad character (%s) cannot be the same as (or a prefix of) the escape block start.", pc)
      })
      escBlockEnd.foreach(ebe => {
        if (pc == ebe || isPrefixOf(pc, ebe))
          context.SDE("The pad character (%s) cannot be the same as (or a prefix of) the escape block end.", pc)
      })
      terminatingMarkup.foreach(tm => {
        if (tm == pc || isPrefixOf(pc, tm)) {
          context.SDE("The pad character (%s) cannot be the same as (or a prefix of) a piece of the terminating markup (%s).", pc, tm)
        }
      })
    })
  }

  private def checkEscCharDistinctness(
    escChar: Maybe[String],
    terminatingMarkup: Seq[String],
    context: ThrowsSDE): Unit = {

    escChar.foreach(ec =>
      terminatingMarkup.foreach(tm => {
        if (tm == ec || isPrefixOf(ec, tm)) {
          context.SDE("The escapeCharacter (%s) cannot be the same as (or a prefix of) a piece of the terminating markup (%s).", ec, tm)
        }
      }))
  }

  private def checkEscEscCharDistinctness(
    escEscChar: Maybe[String],
    escBlockStart: Maybe[String],
    escBlockEnd: Maybe[String],
    terminatingMarkup: Seq[String],
    context: ThrowsSDE): Unit = {

    escEscChar.foreach(eec => {
      // GeneralPurposeFormat seems to disagree with the below, commenting out for now.
      //        escBlockStart match {
      //          case Some(ebs) if eec == ebs || isPrefixOf(eec, ebs) =>
      //            context.SDE("The escapeEscapeCharacter (%s) cannot be the same as (or a prefix of) the escape block start.",eec)
      //          case _ => // Nothing to do
      //        }
      //        escBlockEnd match {
      //          case Some(ebe) if eec == ebe || isPrefixOf(eec, ebe) =>
      //            context.SDE("The escapeEscapeCharacter (%s) cannot be the same as (or a prefix of) the escape block end.",eec)
      //          case _ => // Nothing to do
      //        }
      terminatingMarkup.foreach(tm => {
        if (tm == eec || isPrefixOf(eec, tm)) {
          context.SDE("The escapeEscapeCharacter (%s) cannot be the same as (or a prefix of) a piece of the terminating markup (%s).", eec, tm)
        }
      })
    })
  }

  private def checkEscapeBlockDistinctness(
    escBlockStart: Maybe[String],
    escBlockEnd: Maybe[String],
    terminatingMarkup: Seq[String],
    context: ThrowsSDE): Unit = {

    escBlockStart.foreach(ebs => terminatingMarkup.foreach(tm => {
      if (tm == ebs || isPrefixOf(ebs, tm)) {
        context.SDE("The escapeBlockStart (%s) cannot be the same as (or a prefix of) a piece of the terminating markup.", ebs)
      }
    }))

    escBlockEnd.foreach(ebe => terminatingMarkup.foreach(tm => {
      if (tm == ebe || isPrefixOf(ebe, tm)) {
        context.SDE("The escapeBlockEnd (%s) cannot be the same as (or a prefix of) a piece of the terminating markup.", ebe)
      }
    }))
  }

  private def checkDelimiterDistinctness_(
    padChar: Maybe[String],
    escChar: Maybe[String],
    escEscChar: Maybe[String],
    escBlockStart: Maybe[String],
    escBlockEnd: Maybe[String],
    terminatingMarkup: Seq[String],
    context: ThrowsSDE): Unit = {

    checkPadCharDistinctness(padChar, escChar, escEscChar, escBlockStart, escBlockEnd, terminatingMarkup, context)
    checkEscCharDistinctness(escChar, terminatingMarkup, context)
    checkEscEscCharDistinctness(escEscChar, escBlockStart, escBlockEnd, terminatingMarkup, context)
    checkEscapeBlockDistinctness(escBlockStart, escBlockEnd, terminatingMarkup, context)
  }

  def checkDelimiterDistinctness(
    escapeSchemeKind: EscapeKind,
    optPadChar: Option[String],
    optEscChar: Option[String], // Could be a DFDL expression
    optEscEscChar: Option[String], // Could be a DFDL expression
    optEscBlkStart: Option[String],
    optEscBlkEnd: Option[String],
    terminatingMarkup: Seq[String],
    context: ThrowsSDE): Unit = {

    // TODO: DFDL-451 - After conversing with Mike B. about this, we're putting this on the backburner.
    // Leaving the code here, just commented out the entry point until we can decide what is the appropriate
    // behavior here.
    //
    //    escapeSchemeKind match {
    //      case EscapeSchemeKind.None =>
    //        checkDelimiterDistinctness_(optPadChar, None, None, None, None, terminatingMarkup, context)
    //      case EscapeSchemeKind.Character =>
    //        checkDelimiterDistinctness_(optPadChar, optEscChar, optEscEscChar, None, None, terminatingMarkup, context)
    //      case EscapeSchemeKind.Block =>
    //        checkDelimiterDistinctness_(optPadChar, None, optEscEscChar,
    //          optEscBlkStart, optEscBlkEnd, terminatingMarkup, context)
    //    }
  }
}
