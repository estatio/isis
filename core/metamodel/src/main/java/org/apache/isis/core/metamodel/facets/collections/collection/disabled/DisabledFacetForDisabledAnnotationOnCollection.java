/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.isis.core.metamodel.facets.collections.collection.disabled;

import org.apache.isis.applib.annotation.Disabled;
import org.apache.isis.applib.annotation.When;
import org.apache.isis.applib.annotation.Where;
import org.apache.isis.core.metamodel.facetapi.FacetHolder;
import org.apache.isis.core.metamodel.facets.members.disabled.DisabledFacet;
import org.apache.isis.core.metamodel.facets.members.disabled.DisabledFacetAbstractImpl;

/**
 * @deprecated
 */
@Deprecated
public class DisabledFacetForDisabledAnnotationOnCollection extends DisabledFacetAbstractImpl {

    public static DisabledFacet create(final Disabled annotation, final FacetHolder facetHolder) {
        return annotation != null
                ? new DisabledFacetForDisabledAnnotationOnCollection(annotation.when(), annotation.where(), annotation.reason(), facetHolder)
                : null;
    }

    private DisabledFacetForDisabledAnnotationOnCollection(final When when, Where where, String reason, final FacetHolder holder) {
        super(when, where, reason, holder);
    }


}