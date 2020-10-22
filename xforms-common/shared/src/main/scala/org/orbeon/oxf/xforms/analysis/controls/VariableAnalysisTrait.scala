package org.orbeon.oxf.xforms.analysis.controls

import org.orbeon.dom.Element
import org.orbeon.oxf.common.ValidationException
import org.orbeon.oxf.xforms.analysis._
import org.orbeon.xforms.XFormsId
import org.orbeon.xforms.XFormsNames._

/**
 * Trait representing a variable element, whether in the model or in the view.
 */
trait VariableAnalysisTrait
  extends ElementAnalysis
     with WithChildrenTrait
     with VariableTrait {

  variableSelf =>

  // Variable name and value
  val name: String =
    variableSelf.element.attributeValueOpt(NAME_QNAME) getOrElse
      (
        throw new ValidationException(
          s"`${element.getQualifiedName}` element must have a `name` attribute",
          ElementAnalysis.createLocationData(element)
        )
      )

  val valueElement: Element = VariableAnalysis.valueOrSequenceElement(variableSelf.element) getOrElse variableSelf.element
  val expressionStringOpt: Option[String] = VariableAnalysis.valueOrSelectAttribute(valueElement)

  // `lazy` because the children are evaluated after the container
  lazy val (hasNestedValue, valueScope, valueNamespaceMapping, valueStaticId) =
    children.find(_.localName == "value") match {
      case Some(valueElem) =>
        (true, valueElem.scope, valueElem.localName, valueElem.staticId)
      case None =>
        (false, variableSelf.scope, variableSelf.namespaceMapping, variableSelf.staticId)
    }

  def variableAnalysis: Option[XPathAnalysis] = valueAnalysis
}

object VariableAnalysis {

  def valueOrSelectAttribute(element: Element): Option[String] =
    element.attributeValueOpt(VALUE_QNAME) orElse element.attributeValueOpt(SELECT_QNAME)

  def valueOrSequenceElement(element: Element): Option[Element] =
    element.elementOpt(XXFORMS_VALUE_QNAME) orElse element.elementOpt(XXFORMS_SEQUENCE_QNAME)

  // See https://github.com/orbeon/orbeon-forms/issues/1104 and https://github.com/orbeon/orbeon-forms/issues/1132
  def variableScopesModelVariables(v: VariableAnalysisTrait): Boolean =
    v.isInstanceOf[ViewTrait] || v.model != (v.parent flatMap (_.model))
}