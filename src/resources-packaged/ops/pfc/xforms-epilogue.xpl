<!-- Copyright (C) 2005-2008 Orbeon, Inc.

    This program is free software; you can redistribute it and/or modify it under the terms of the
    GNU Lesser General Public License as published by the Free Software Foundation; either version
    2.1 of the License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
    without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
    See the GNU Lesser General Public License for more details.

    The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
-->
<p:config xmlns:p="http://www.orbeon.com/oxf/pipeline"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:oxf="http://www.orbeon.com/oxf/processors"
    xmlns:xforms="http://www.w3.org/2002/xforms"
    xmlns:xxforms="http://orbeon.org/oxf/xml/xforms"
    xmlns:xhtml="http://www.w3.org/1999/xhtml">

    <p:param type="input" name="data"/>
    <p:param type="input" name="model-data"/>
    <p:param type="input" name="instance"/>
    <p:param type="input" name="xforms-model"/>
    <p:param type="output" name="xformed-data"/>

    <!-- Get request information -->
    <p:processor name="oxf:request">
        <p:input name="config">
            <config>
                <include>/request/container-type</include>
                <include>/request/request-path</include>
                <include>/request/context-path</include>
            </config>
        </p:input>
        <p:output name="data" id="request-info"/>
    </p:processor>

    <!-- Annotate XForms elements and generate XHTML if necessary -->
    <!-- TODO: put here processor detecting XForms model -->
    <p:choose href="#data">
        <!-- ========== Test for NG XForms engine ========== -->
        <!-- NOTE: in the future, we may want to support "XForms within XML" so this test will have to be modified -->
        <p:when test="/xhtml:html/xhtml:head/xforms:model"><!-- TODO: test on result of processor above -->
            <!-- Handle widgets -->

            <!--<p:processor name="oxf:sax-logger">-->
                <!--<p:input name="data" href="#data"/>-->
                <!--<p:output name="data" id="data2"/>-->
            <!--</p:processor>-->

            <!-- Apply XForms widgets if needed -->
            <p:choose href="#request-info"><!-- dummy test input -->
                <p:when test="p:property('oxf.epilogue.xforms.widgets')">
                    <p:processor name="oxf:xslt">
                        <!--<p:input name="data" href="#data2"/>-->
                        <p:input name="data" href="#data"/>
                        <p:input name="config" href="/config/xforms-widgets.xsl"/>
                        <p:output name="data" id="widgeted-view"/>
                    </p:processor>
                </p:when>
                <p:otherwise>
                    <!-- No theme -->
                    <p:processor name="oxf:identity">
                        <p:input name="data" href="#data"/>
                        <p:output name="data" id="widgeted-view"/>
                    </p:processor>
                </p:otherwise>
            </p:choose>

            <!-- Get current namespace to enable caching per portlet -->
            <p:processor name="oxf:request">
                <p:input name="config">
                    <config>
                        <include>/request/container-namespace</include>
                    </config>
                </p:input>
                <p:output name="data" id="namespace"/>
            </p:processor>

            <!-- Native XForms Initialization -->
            <p:processor name="oxf:xforms-to-xhtml">
                <p:input name="annotated-document" href="#widgeted-view"/>
                <p:input name="data" href="#model-data"/>
                <!-- This input adds a dependency on the container namespace. Keep it for portlets. -->
                <p:input name="namespace" href="#namespace"/>
                <p:input name="instance" href="#instance"/>
                <p:output name="document" id="xhtml-data"/>
            </p:processor>

            <!--<p:processor name="oxf:sax-logger">-->
                <!--<p:input name="data" href="#xhtml-data"/>-->
                <!--<p:output name="data" id="xhtml-data2"/>-->
            <!--</p:processor>-->

            <!-- XInclude processing to add error dialog configuration and more -->
            <p:processor name="oxf:xinclude">
                <p:input name="config" href="#xhtml-data"/>
                <p:output name="data" ref="xformed-data"/>
            </p:processor>
        </p:when>
        <p:otherwise>
            <!-- ========== No XForms ========== -->
            <p:processor name="oxf:identity">
                <p:input name="data" href="#data"/>
                <p:output name="data" ref="xformed-data"/>
            </p:processor>
        </p:otherwise>
    </p:choose>

</p:config>
