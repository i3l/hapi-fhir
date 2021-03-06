package ca.uhn.fhir.rest.method;

/*
 * #%L
 * HAPI FHIR - Core Library
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.model.dstu.valueset.RestfulOperationSystemEnum;
import ca.uhn.fhir.model.dstu.valueset.RestfulOperationTypeEnum;
import ca.uhn.fhir.model.valueset.BundleTypeEnum;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.client.BaseHttpClientInvocation;
import ca.uhn.fhir.rest.server.Constants;
import ca.uhn.fhir.rest.server.IBundleProvider;
import ca.uhn.fhir.rest.server.IDynamicSearchResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

public class DynamicSearchMethodBinding extends BaseResourceReturningMethodBinding {

	private IDynamicSearchResourceProvider myProvider;
	private List<RuntimeSearchParam> mySearchParameters;
	private HashSet<String> myParamNames;
	private Integer myIdParamIndex;

	public DynamicSearchMethodBinding(Class<? extends IBaseResource> theReturnResourceType, Method theMethod, FhirContext theConetxt, IDynamicSearchResourceProvider theProvider) {
		super(theReturnResourceType, theMethod, theConetxt, theProvider);

		myProvider = theProvider;
		mySearchParameters = myProvider.getSearchParameters();

		myParamNames = new HashSet<String>();
		for (RuntimeSearchParam next : mySearchParameters) {
			myParamNames.add(next.getName());
		}

		myIdParamIndex = MethodUtil.findIdParameterIndex(theMethod);

	}

	@Override
	protected BundleTypeEnum getResponseBundleType() {
		return BundleTypeEnum.SEARCHSET;
	}


	@Override
	public List<IParameter> getParameters() {
		List<IParameter> retVal = new ArrayList<IParameter>(super.getParameters());
		
		for (RuntimeSearchParam next : mySearchParameters) {
			// TODO: what is this?
		}
		
		return retVal;
	}

	@Override
	public ReturnTypeEnum getReturnType() {
		return ReturnTypeEnum.BUNDLE;
	}

	@Override
	public IBundleProvider invokeServer(RequestDetails theRequest, Object[] theMethodParams) throws InvalidRequestException, InternalErrorException {
		if (myIdParamIndex != null) {
			theMethodParams[myIdParamIndex] = theRequest.getId();
		}

		Object response = invokeServerMethod(theMethodParams);
		return toResourceList(response);
	}

	@Override
	public RestfulOperationTypeEnum getResourceOperationType() {
		return RestfulOperationTypeEnum.SEARCH_TYPE;
	}

	@Override
	public RestfulOperationSystemEnum getSystemOperationType() {
		return null;
	}

	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(DynamicSearchMethodBinding.class);

	@Override
	public boolean incomingServerRequestMatchesMethod(RequestDetails theRequest) {
		if (!theRequest.getResourceName().equals(getResourceName())) {
			ourLog.trace("Method {} doesn't match because resource name {} != {}", getMethod().getName(), theRequest.getResourceName(), getResourceName());
			return false;
		}
		if (theRequest.getId() != null && myIdParamIndex == null) {
			ourLog.trace("Method {} doesn't match because ID is not null: {}", theRequest.getId());
			return false;
		}
		if (theRequest.getRequestType() == RequestTypeEnum.GET && theRequest.getOperation() != null && !Constants.PARAM_SEARCH.equals(theRequest.getOperation())) {
			ourLog.trace("Method {} doesn't match because request type is GET but operation is not null: {}", theRequest.getId(), theRequest.getOperation());
			return false;
		}
		if (theRequest.getRequestType() == RequestTypeEnum.POST && !Constants.PARAM_SEARCH.equals(theRequest.getOperation())) {
			ourLog.trace("Method {} doesn't match because request type is POST but operation is not _search: {}", theRequest.getId(), theRequest.getOperation());
			return false;
		}
		if (theRequest.getRequestType() != RequestTypeEnum.GET && theRequest.getRequestType() != RequestTypeEnum.POST) {
			ourLog.trace("Method {} doesn't match because request type is {}", getMethod());
			return false;
		}
		if (theRequest.getCompartmentName() != null) {
			ourLog.trace("Method {} doesn't match because it is for compartment {}", new Object[] { getMethod(), theRequest.getCompartmentName() });
			return false;
		}

		for (String next : theRequest.getParameters().keySet()) {
			if (next.charAt(0) == '_') {
				continue;
			}
			String nextQualified = next;
			int colonIndex = next.indexOf(':');
			int dotIndex = next.indexOf('.');
			if (colonIndex != -1 || dotIndex != -1) {
				int index;
				if (colonIndex != -1 && dotIndex != -1) {
					index = Math.min(colonIndex, dotIndex);
				} else {
					index = (colonIndex != -1) ? colonIndex : dotIndex;
				}
				next = next.substring(0, index);
			}
			if (!myParamNames.contains(next)) {
				ourLog.trace("Method {} doesn't match because has parameter {}", new Object[] { getMethod(), nextQualified });
				return false;
			}
		}

		return true;
	}

	@Override
	public BaseHttpClientInvocation invokeClient(Object[] theArgs) throws InternalErrorException {
		// there should be no way to call this....
		throw new UnsupportedOperationException("Dynamic search methods are only used for server implementations");
	}

	public Collection<? extends RuntimeSearchParam> getSearchParams() {
		return mySearchParameters;
	}

}
