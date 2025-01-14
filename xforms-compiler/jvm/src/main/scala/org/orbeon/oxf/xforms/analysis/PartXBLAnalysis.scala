/**
 *  Copyright (C) 2007 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.analysis

import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.xforms.analysis.controls.{AttributeControl, ComponentControl}
import org.orbeon.oxf.xforms.analysis.model.StaticBind
import org.orbeon.oxf.xforms.xbl.{AbstractBinding, XBLAssets}
import org.orbeon.xforms.xbl.Scope
import org.orbeon.xforms.{Constants, XFormsId}

import scala.collection.mutable


trait PartXBLAnalysis extends TransientState {

  self =>

  def startScope: Scope
  def metadata: Metadata

  val abstractBindingsWithGlobals = mutable.ArrayBuffer[AbstractBinding]()
  val allGlobals                  = mutable.ArrayBuffer[Global]()
  def iterateGlobals: Iterator[Global] = allGlobals.iterator

  // This function is not called as of 2011-06-28 but if/when we support removing scopes, check these notes:
  // - deindex prefixed ids => Scope
  // - remove models associated with scope
  // - remove control analysis
  // - deindex scope id => Scope
  //def removeScope(scope: Scope) = ???

  // NOTE: Can't update abstractBindings, allScripts, allStyles, allGlobals without checking all again, so for now
  // leave that untouched.

  private[PartXBLAnalysis] val scopesById              = mutable.HashMap[String, Scope]()
  private[PartXBLAnalysis] val prefixedIdToXBLScopeMap = mutable.HashMap[String, Scope]()

  def initializeScopes(): Unit = {
    // Add existing ids to scope map
    val prefix = startScope.fullPrefix
    metadata.idGenerator.add(Constants.DocumentId) // top-level is not added to the id generator until now
    for {
      staticId   <- metadata.idGenerator.ids
      prefixedId = prefix + staticId
    } locally {
      mapScopeIds(staticId, prefixedId, startScope, ignoreIfPresent = false)
    }

    registerScope(startScope)
  }

  def dumpScopes(): Unit = {
    println("scopes:")
    println(
      prefixedIdToXBLScopeMap.toList.map{
        case (id, scope) => s"$id -> ${scope.scopeId}"
      }.sorted.mkString("\n")
    )
  }

  def newScope(parent: Scope, scopeId: String): Scope =
    registerScope(new Scope(Some(parent ensuring (_ ne null)), scopeId))

  private def registerScope(scope: Scope) = {
    assert(! scopesById.contains(scope.scopeId))

    scopesById += scope.scopeId -> scope
    scope
  }

  // Used by `xxf:dynamic`
  def deregisterScope(scope: Scope): Unit =
    scopesById -= scope.scopeId

  def mapScopeIds(staticId: String, prefixedId: String, scope: Scope, ignoreIfPresent: Boolean): Unit =
    if (prefixedIdToXBLScopeMap.contains(prefixedId)) {
      if (! ignoreIfPresent)
        throw new OXFException(s"Duplicate id found for prefixed id `$prefixedId`")
    } else {
      scope += staticId -> prefixedId
      prefixedIdToXBLScopeMap += prefixedId -> scope
    }

  // Deindex the given control's XBL-related information
  def unmapScopeIds(control: ElementAnalysis): Unit = {
    control match {
      case component: ComponentControl =>
        component.bindingOpt foreach { binding =>
          deregisterScope(binding.innerScope)
        }
      case attribute: AttributeControl =>
        control.scope -= attribute.forStaticId
        prefixedIdToXBLScopeMap -= attribute.forPrefixedId
      case bind: StaticBind =>
        bind.iterateNestedIds foreach { mipId =>
          control.scope -= mipId
          prefixedIdToXBLScopeMap -= XFormsId.getRelatedEffectiveId(control.prefixedId, mipId)
        }
      case _ =>
    }
    control.scope -= control.staticId
    prefixedIdToXBLScopeMap -= control.prefixedId
  }

  def containingScope(prefixedId: String): Scope = {
    val prefix = XFormsId.getEffectiveIdPrefix(prefixedId)

    val scopeId = if (prefix.isEmpty) "" else prefix.init
    scopesById.get(scopeId).orNull
  }

  def scopeForPrefixedId(prefixedId: String): Scope =
    prefixedIdToXBLScopeMap.get(prefixedId).orNull // NOTE: only one caller tests for null: XBLContainer.findResolutionScope

  def allXblAssetsMaybeDuplicates: Iterable[XBLAssets] =
    metadata.allBindingsMaybeDuplicates map { binding =>
      XBLAssets(binding.commonBinding.cssName, binding.scripts, binding.styles)
    }

  override def freeTransientState(): Unit = {
    super.freeTransientState()
    metadata.commitBindingIndex()
  }
}