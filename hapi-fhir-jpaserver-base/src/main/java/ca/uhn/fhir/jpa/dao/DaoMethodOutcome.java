package ca.uhn.fhir.jpa.dao;

/*
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2015 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.jpa.entity.BaseResourceEntity;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.rest.api.MethodOutcome;

public class DaoMethodOutcome extends MethodOutcome {

    private BaseResourceEntity myEntity;
    private IResource myResource;

    public BaseResourceEntity getEntity() {
        return myEntity;
    }

    public IResource getResource() {
        return myResource;
    }

    @Override
    public DaoMethodOutcome setCreated(Boolean theCreated) {
        super.setCreated(theCreated);
        return this;
    }

    public DaoMethodOutcome setEntity(BaseResourceEntity entity) {
        myEntity = entity;
        return this;
    }

    public DaoMethodOutcome setResource(IResource theResource) {
        myResource = theResource;
        return this;
    }

}
