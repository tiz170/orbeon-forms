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
package org.orbeon.oxf.fr.persistence.db

import javax.naming.{Context, InitialContext, NameAlreadyBoundException}

import org.apache.commons.dbcp.BasicDataSource
import org.orbeon.oxf.util.CoreUtils._

import scala.collection.immutable

// Utility to setup datasources outside of a servlet container environment, such as when running tests.
object DataSourceSupport {

  // Run the given thunk in the context of the datasources specified by the given descriptors.
  // This sets a JNDI context, binds the datasources, runs the thunk, unbind the datasources, and
  // does some JNDI context cleanup.
  def withDatasources[T](datasources: immutable.Seq[DatasourceDescriptor])(thunk: ⇒ T): T = {
    val originalProperties = setupInitialContextForJDBC()
    datasources foreach bindDatasource
    val result = thunk
    datasources foreach unbindDatasource
    clearInitialContextForJDBC(originalProperties)
    result
  }

  private val BuildNumber = System.getenv("TRAVIS_BUILD_NUMBER")

  def orbeonUserWithBuildNumber    = s"orbeon_$BuildNumber"

  private val NamingPrefix = "java:comp/env/jdbc/"

  private def setupInitialContextForJDBC(): List[(String, Option[String])] = {

    val originalProperties = List(Context.INITIAL_CONTEXT_FACTORY, Context.URL_PKG_PREFIXES) map { name ⇒
      name → Option(System.getProperty(name))
    }

    System.setProperty(Context.INITIAL_CONTEXT_FACTORY, "org.apache.naming.java.javaURLContextFactory")
    System.setProperty(Context.URL_PKG_PREFIXES,        "org.apache.naming")

    def createSubcontextIgnoreIfBound(ic: InitialContext, name: String) =
      try {
        ic.createSubcontext(name)
      } catch {
        case e: NameAlreadyBoundException ⇒ // ignore
      }

    new InitialContext                                         |!>
      (createSubcontextIgnoreIfBound(_, "java:"             )) |!>
      (createSubcontextIgnoreIfBound(_, "java:comp"         )) |!>
      (createSubcontextIgnoreIfBound(_, "java:comp/env"     )) |!>
      (createSubcontextIgnoreIfBound(_, "java:comp/env/jdbc"))

    originalProperties
  }

  private def clearInitialContextForJDBC(originalProperties: List[(String, Option[String])]) =
    originalProperties foreach {
      case (name, Some(value)) ⇒ System.setProperty(name, value)
      case (name, None)        ⇒ System.clearProperty(name)
    }

  private def bindDatasource(ds: DatasourceDescriptor): Unit =
    (new InitialContext).rebind(
      NamingPrefix + ds.name,
      new BasicDataSource                   |!>
        (_.setDriverClassName(ds.driver  )) |!>
        (_.setUrl            (ds.url     )) |!>
        (_.setUsername       (ds.username)) |!>
        (_.setPassword       (ds.password))
    )

  private  def unbindDatasource(ds: DatasourceDescriptor): Unit =
    (new InitialContext).unbind(NamingPrefix + ds.name)
}
