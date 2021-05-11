package org.orbeon.oxf.util

import org.orbeon.oxf.externalcontext.ExternalContext.Request
import org.scalajs.dom
import PathUtils._
import org.orbeon.oxf.externalcontext.URLRewriter

import java.net.URI
import java.{util => ju}


object URLRewriterUtils {

  // TODO: placeholder, does it matter for Scala.js?
  def isResourcesVersioned = false

  // TODO: Analyze usage and handle rewriting modes as needed
  def rewriteResourceURL(
    request      : Request,
    urlString    : String,
    pathMatchers : ju.List[PathMatcher],
    rewriteMode  : Int
  ): String = {

    // We want to rewrite for example `/xbl/etc.`
    val stringNoSlash = if (! urlString.startsWith("//")) urlString.dropStartingSlash else urlString
    val uriNoSlash    = URI.create(stringNoSlash)

    if (uriNoSlash.getScheme ne null) {
      urlString
    } else {
      if (rewriteMode == URLRewriter.REWRITE_MODE_ABSOLUTE_PATH_NO_CONTEXT) {
        // Only used directly by `XFormsOutputControl`
        urlString
      } else {
        val base = URI.create(dom.window.location.href)
        if (base.getScheme == "http" || base.getScheme == "https")
          base.resolve("_/" + stringNoSlash).toString // mostly for when we load the offline template from Orbeon Forms for testing
        else
          base.resolve(uriNoSlash).toString           // regular offline case where resources are loaded from `file:`
      }
    }
  }

  def rewriteServiceURL(
    request     : Request,
    urlString   : String,
    rewriteMode : Int
  ): String =
    if (PathUtils.urlHasProtocol(urlString))
      urlString
    else
      throw new NotImplementedError("rewriteServiceURL")

  // Used by `rewriteResourceURL` for `XFormsOutputControl`.
  // Q: Does anything make sense there?
  def getPathMatchers: ju.List[PathMatcher] =
    ju.Collections.emptyList[PathMatcher]
}
