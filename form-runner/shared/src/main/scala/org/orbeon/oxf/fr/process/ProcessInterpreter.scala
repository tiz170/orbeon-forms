/**
 *  Copyright (C) 2013 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.fr.process

import org.orbeon.exception.OrbeonFormatter
import org.orbeon.oxf.externalcontext.ExternalContext
import org.orbeon.oxf.fr.XMLNames
import org.orbeon.oxf.fr.process.ProcessParser.{ActionNode, ConditionNode, GroupNode, _}
import org.orbeon.oxf.logging.LifecycleLogger
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.oxf.util.TryUtils._
import org.orbeon.oxf.util.{CoreCrossPlatformSupport, IndentedLogger, Logging}
import org.orbeon.xforms.XFormsNames._
import org.orbeon.oxf.xml.XMLConstants.{XHTML_PREFIX, XHTML_SHORT_PREFIX, XSD_PREFIX}
import org.orbeon.oxf.xml.{XMLConstants, XMLUtils}
import org.orbeon.oxf.{util => u}
import org.orbeon.saxon.functions.FunctionLibrary
import org.orbeon.saxon.om.Item
import org.orbeon.saxon.value.BooleanValue
import org.orbeon.scaxon.XPath._
import org.orbeon.xml.NamespaceMapping

import scala.annotation.tailrec
import scala.util.control.{Breaks, ControlThrowable, NonFatal}
import scala.util.{Failure, Success, Try}


// Independent process interpreter
trait ProcessInterpreter extends Logging {

  import ProcessInterpreter._

  val EmptyActionParams: ActionParams = Map.empty

  // Must be overridden by implementation
  def currentXFormsDocumentId: String
  def findProcessByName(scope: String, name: String): Option[String]
  def processError(t: Throwable): Unit
  def xpathContext: Item
  implicit def xpathFunctionLibrary: FunctionLibrary
  def xpathFunctionContext: u.FunctionContext
  def writeSuspendedProcess(process: String): Unit
  def readSuspendedProcess: String
  def createUniqueProcessId: String = CoreCrossPlatformSupport.randomHexId
  def transactionStart(): Unit
  def transactionRollback(): Unit
  implicit def logger: IndentedLogger

  // May be overridden by implementation
  def extensionActions: Iterable[(String, Action)] = Nil
  def beforeProcess(): Try[Any] = Try(())
  def afterProcess():  Try[Any] = Try(())

  private object ProcessRuntime {

    import org.orbeon.oxf.util.DynamicVariable

    val StandardActions: Map[String, Action] = Map(
      "success"  -> trySuccess,
      "failure"  -> tryFailure,
      "process"  -> tryProcess,
      "suspend"  -> trySuspend,
      "resume"   -> tryResume,
      "abort"    -> tryAbort,
      "rollback" -> tryRollback,
      "nop"      -> tryNop
    )

    val AllAllowedActions = StandardActions ++ extensionActions

    // Keep stack frames for the execution of action. They can nest with sub-processes.
    val processStackDyn = new DynamicVariable[Process]
    val processBreaks   = new Breaks

    // Scope an empty stack around a process execution
    def withEmptyStack[T](scope: String)(body: => T): T = {
      processStackDyn.withValue(Process(scope, createUniqueProcessId, Nil)) {
        body
      }
    }

    // Push a stack frame, run the body, and pop the frame
    def withStackFrame[T](group: GroupNode, programCounter: Int)(body: => T): T = {
      processStackDyn.value.get.frames = StackFrame(group, programCounter) :: processStackDyn.value.get.frames
      try body
      finally processStackDyn.value.get.frames = processStackDyn.value.get.frames.tail
    }

    // Return a process string which contains the continuation of the process after the current action
    def serializeContinuation: String = {

      val process = processStackDyn.value.get

      // Find the continuation, which is the concatenation of the continuation of all the sub-processes up to the
      // top-level process.
      val continuation =
        process.frames flatMap {
          case StackFrame(group, pos) =>
            group.rest drop pos flatMap { case (combinator, expr) =>
              List(combinator.name, expr.serialize)
            }
        }

      // Continuation is either empty or starts with a combinator. We prepend the (always successful) "nop".
      val serializedContinuation = "nop" :: continuation mkString ("(", " ", ")")
      process.processId + '|' + serializedContinuation
    }

    case class Process(scope: String, processId: String, var frames: List[StackFrame])
    case class StackFrame(group: GroupNode, actionCounter: Int)

    def runSubProcess(process: String): Try[Any] = {

      def runAction(action: ActionNode) =
        withDebug("process: running action", List("action" -> action.toString)) {
          // Push and pop the stack frame (for suspend/resume)
          val result = (
            AllAllowedActions
            getOrElse (action.name, (_: ActionParams) => tryProcess(Map(Some("name") -> action.name)))
            apply     action.params
          )

          debugResults(List("result" -> (if (result.isSuccess) "success" else "failure")))

          result
        }

      def runGroup(group: GroupNode): Try[Any] = {
        val GroupNode(expr, rest) = group
        runGroupRest(group, 1, withStackFrame(group, 0) { runExpr(expr) }, rest)
      }

      @tailrec def runGroupRest(group: GroupNode, pos: Int, tried: Try[Any], rest: List[(Combinator, ExprNode)]): Try[Any] =
        if (rest.nonEmpty) {
          val (nextCombinator, nextExpr) = rest.head

          val newTried =
            withStackFrame(group, pos) {
              nextCombinator match {
                case ThenCombinator =>
                  debug("process: combining with then", List("action" -> nextExpr.toString))
                  tried flatMap (_ => runExpr(nextExpr))
                case RecoverCombinator =>
                  debug("process: combining with recover", List("action" -> nextExpr.toString))
                  tried recoverWith {
                    case t: ControlThrowable =>
                      debug("process: rethrowing ControlThrowable")
                      throw t
                    case NonFatal(t) =>
                      debug("process: recovering", List("throwable" -> OrbeonFormatter.format(t)))
                      runExpr(nextExpr)
                  }
              }
            }

          runGroupRest(group, pos + 1, newTried, rest.tail)
        } else
          tried

      def runCondition(condition: ConditionNode) = {
        val ConditionNode(xpath, thenBranch, elseBranch) = condition
        Try {
          evaluateBoolean(xpath)
        } flatMap { cond =>
          if (cond)
            runExpr(thenBranch)
          else if (elseBranch.isDefined)
            runExpr(elseBranch.get)
          else
            Success(())
        }
      }

      def runExpr(expr: ExprNode): Try[Any] =
        expr match {
          case e: ActionNode    => runAction(e)
          case e: GroupNode     => runGroup(e)
          case e: ConditionNode => runCondition(e)
        }

      def parseProcess(process: String) =
        process.nonAllBlank option ProcessParser.parse(process)

      parseProcess(process) match {
        case Some(expr) =>
          runExpr(expr)
        case None =>
          debug("process: empty process, canceling process")
          Success(())
      }
    }
  }

  import ProcessRuntime._
  import processBreaks._

  private def rawProcessByName(scope: String, name: String) =
    findProcessByName(scope, name) getOrElse
    (throw new IllegalArgumentException(s"Non-existing process: $name in scope $scope"))

  // Main entry point for starting a process associated with a named button
  def runProcessByName(scope: String, name: String): Try[Any] = {
    implicit val ec: ExternalContext = CoreCrossPlatformSupport.externalContext
    LifecycleLogger.withEventAssumingRequest("fr", "process", List("uuid" -> currentXFormsDocumentId, "scope" -> scope, "name" -> name)) {
      runProcess(scope, rawProcessByName(scope, name))
    }
  }

  // Main entry point for starting a literal process
  def runProcess(scope: String, process: String): Try[Any] =
    withDebug("process: running", List("process" -> process)) {
      transactionStart()
      // Scope the process (for suspend/resume)
      withEmptyStack(scope) {
        beforeProcess() flatMap { _ =>
          tryBreakable {
            runSubProcess(process)
          } catchBreak {
            Success(()) // to change once `tryFailure` is supported
          }
        } doEitherWay {
          afterProcess()
        } recoverWith { case t =>
          // Log and send a user error if there is one
          // NOTE: In the future, it would be good to provide the user with an error id.
          error(OrbeonFormatter.format(t))
          Try(processError(t))
          Failure(t)
        }
      }
    }

  // Id of the currently running process
  def runningProcessId: Option[String] = processStackDyn.value map (_.processId)

  // Interrupt the process and complete with a success
  // We will rethrow this as we explicitly check for ControlThrowable above
  def trySuccess(params: ActionParams): Try[Any] = Try(break())

  // Interrupt the process and complete with a failure
  def tryFailure(params: ActionParams): Try[Any] = ???

  // Run a sub-process
  def tryProcess(params: ActionParams): Try[Any] =
    Try(paramByNameOrDefault(params, "name").get) map
    (rawProcessByName(processStackDyn.value.get.scope, _)) flatMap
    runSubProcess

  // Suspend the process
  def trySuspend(params: ActionParams): Try[Any] = Try {
    writeSuspendedProcess(serializeContinuation)
    Success(())
  } flatMap
    (_ => trySuccess(EmptyActionParams))

  // Resume a process
  def tryResume(params: ActionParams): Try[Any] =
    readSuspendedProcess.splitTo[List]("|") match {
      case processId :: continuation =>
        // TODO: Restore processId
        writeSuspendedProcess("")
        runSubProcess(continuation mkString "|")
      case other =>
        error(s"error finding process to resume: `$other`")
        Failure(new IllegalArgumentException)
    }

  // Abort a suspended process
  def tryAbort(params: ActionParams): Try[Any] =
    Try(writeSuspendedProcess(""))

  // Rollback the current transaction
  def tryRollback(params: ActionParams): Try[Any] =
    Try {

      val tokens = paramByNameOrDefault(params, "changes") map (_.tokenizeToSet) getOrElse Set.empty

      if (tokens != Set("in-memory-form-data"))
        throw new IllegalArgumentException(s"""`rollback` action must have a `changes = "in-memory-form-data"` parameter""")

      transactionRollback()
    }

  // Don't do anything
  def tryNop(params: ActionParams): Try[Any] =
    Success(())

  def evaluateBoolean(expr: String, item: Item = xpathContext): Boolean =
    evaluateOne(
      expr = u.StaticXPath.makeBooleanExpression(expr),
      item = item
    ).asInstanceOf[BooleanValue].getBooleanValue

  def evaluateString(
    expr    : String,
    item    : Item             = xpathContext,
    mapping : NamespaceMapping = ProcessInterpreter.StandardNamespaceMapping
  ): String =
    evaluateOne(
      expr    = u.StaticXPath.makeStringExpression(expr),
      item    = item,
      mapping = mapping
    ).getStringValue

  def evaluateOne(
    expr    : String,
    item    : Item             = xpathContext,
    mapping : NamespaceMapping = ProcessInterpreter.StandardNamespaceMapping
  ): Item =
    evalOne(
      item            = item,
      expr            = expr,
      namespaces      = mapping,
      functionContext = xpathFunctionContext
    )

  def evaluate(expr: String, item: Item = xpathContext): collection.Seq[Any] =
    eval(
      item            = item,
      expr            = expr,
      namespaces      = ProcessInterpreter.StandardNamespaceMapping,
      functionContext = xpathFunctionContext
    )

  def evaluateValueTemplate(valueTemplate: String): String =
    if (! XMLUtils.maybeAVT(valueTemplate))
      valueTemplate
    else
      evalValueTemplate(
        item            = xpathContext,
        expr            = valueTemplate,
        namespaces      = ProcessInterpreter.StandardNamespaceMapping,
        functionContext = xpathFunctionContext
      )
}

object ProcessInterpreter {

  val StandardNamespaceMapping =
    NamespaceMapping(
      Map(
        XSD_PREFIX           -> XMLConstants.XSD_URI,
        XFORMS_PREFIX        -> XFORMS_NAMESPACE_URI,
        XFORMS_SHORT_PREFIX  -> XFORMS_NAMESPACE_URI,
        XXFORMS_PREFIX       -> XXFORMS_NAMESPACE_URI,
        XXFORMS_SHORT_PREFIX -> XXFORMS_NAMESPACE_URI,
        XHTML_PREFIX         -> XMLConstants.XHTML_NAMESPACE_URI,
        XHTML_SHORT_PREFIX   -> XMLConstants.XHTML_NAMESPACE_URI,
        XHTML_PREFIX         -> XMLConstants.XHTML_NAMESPACE_URI,
        XHTML_SHORT_PREFIX   -> XMLConstants.XHTML_NAMESPACE_URI,
        XMLNames.FRPrefix    -> XMLNames.FR,
        "grid-migration"     -> "java:org.orbeon.oxf.fr.GridDataMigration" // TODO: should be from properties file
      )
    )

  type ActionParams = Map[Option[String], String]
  type Action       = ActionParams => Try[Any]

  def paramByName(params: ActionParams, name: String): Option[String] =
    params.get(Some(name))

  def booleanParamByName(params: ActionParams, name: String, default: Boolean): Boolean =
    params.get(Some(name)) map (_ == "true") getOrElse default

  def paramByNameOrDefault(params: ActionParams, name: String): Option[String] =
    params.get(Some(name)) orElse params.get(None)

  def requiredParamByName(params: ActionParams, action: String, name: String): String =
    params.getOrElse(Some(name), missingArgument(action, name))

  // TODO: Obtain action name automatically.
  def missingArgument(action: String, name: String) =
    throw new IllegalArgumentException(s"$action: `$name` parameter is required")
}