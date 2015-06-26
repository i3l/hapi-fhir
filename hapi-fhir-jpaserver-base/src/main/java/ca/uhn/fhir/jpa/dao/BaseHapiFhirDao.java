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

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.persistence.NoResultException;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;

import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.jpa.entity.BaseHasResource;
import ca.uhn.fhir.jpa.entity.BaseResourceIndexedSearchParam;
import ca.uhn.fhir.jpa.entity.BaseTag;
import ca.uhn.fhir.jpa.entity.ForcedId;
import ca.uhn.fhir.jpa.entity.ResourceEncodingEnum;
import ca.uhn.fhir.jpa.entity.ResourceHistoryTable;
import ca.uhn.fhir.jpa.entity.ResourceHistoryTag;
import ca.uhn.fhir.jpa.entity.ResourceIndexedSearchParamDate;
import ca.uhn.fhir.jpa.entity.ResourceIndexedSearchParamNumber;
import ca.uhn.fhir.jpa.entity.ResourceIndexedSearchParamQuantity;
import ca.uhn.fhir.jpa.entity.ResourceIndexedSearchParamString;
import ca.uhn.fhir.jpa.entity.ResourceIndexedSearchParamToken;
import ca.uhn.fhir.jpa.entity.ResourceLink;
import ca.uhn.fhir.jpa.entity.ResourceTable;
import ca.uhn.fhir.jpa.entity.ResourceTag;
import ca.uhn.fhir.jpa.entity.TagDefinition;
import ca.uhn.fhir.jpa.entity.TagTypeEnum;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.api.ResourceMetadataKeyEnum;
import ca.uhn.fhir.model.api.Tag;
import ca.uhn.fhir.model.api.TagList;
import ca.uhn.fhir.model.base.composite.BaseCodingDt;
import ca.uhn.fhir.model.base.composite.BaseResourceReferenceDt;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.method.RestSearchParameterTypeEnum;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.util.FhirTerser;

public class BaseHapiFhirDao extends BaseFhirDao {

	public static final String NS_JPA_PROFILE = "https://github.com/jamesagnew/hapi-fhir/ns/jpa/profile";
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(BaseHapiFhirDao.class);

	public static final String UCUM_NS = "http://unitsofmeasure.org";

	private ISearchParamExtractor mySearchParamExtractor;

	protected void createForcedIdIfNeeded(ResourceTable entity, IdDt id) {
		if (id.isEmpty() == false && id.hasIdPart()) {
			if (isValidPid(id)) {
				return;
			}
			ForcedId fid = new ForcedId();
			fid.setForcedId(id.getIdPart());
			fid.setResource(entity);
			entity.setForcedId(fid);
		}
	}

	protected List<ResourceLink> extractResourceLinks(ResourceTable theEntity, IResource theResource) {
		ArrayList<ResourceLink> retVal = new ArrayList<ResourceLink>();

		RuntimeResourceDefinition def = getContext().getResourceDefinition(theResource);
		for (RuntimeSearchParam nextSpDef : def.getSearchParams()) {
			if (nextSpDef.getParamType() != RestSearchParameterTypeEnum.REFERENCE) {
				continue;
			}

			String nextPathsUnsplit = nextSpDef.getPath();
			if (isBlank(nextPathsUnsplit)) {
				continue;
			}

			boolean multiType = false;
			if (nextPathsUnsplit.endsWith("[x]")) {
				multiType = true;
			}

			for (Object nextObject : extractValues(nextPathsUnsplit, theResource)) {
				if (nextObject == null) {
					continue;
				}

				ResourceLink nextEntity;
				if (nextObject instanceof BaseResourceReferenceDt) {
					BaseResourceReferenceDt nextValue = (BaseResourceReferenceDt) nextObject;
					if (nextValue.isEmpty()) {
						continue;
					}
					if (nextValue.getReference().isEmpty() || nextValue.getReference().getValue().startsWith("#")) {
						// This is a blank or contained resource reference
						continue;
					}

					String typeString = nextValue.getReference().getResourceType();
					if (isBlank(typeString)) {
						throw new InvalidRequestException("Invalid resource reference found at path[" + nextPathsUnsplit + "] - Does not contain resource type - "
								+ nextValue.getReference().getValue());
					}
					Class<? extends IBaseResource> type = getContext().getResourceDefinition(typeString).getImplementingClass();
					String id = nextValue.getReference().getIdPart();
					if (StringUtils.isBlank(id)) {
						continue;
					}

					IFhirResourceDao<?> dao = getDao(type);
					if (dao == null) {
						StringBuilder b = new StringBuilder();
						b.append("This server (version ");
						b.append(getContext().getVersion().getVersion());
						b.append(") is not able to handle resources of type[");
						b.append(nextValue.getReference().getResourceType());
						b.append("] - Valid resource types for this server: ");
						b.append(getMyResourceTypeToDao().keySet().toString());

						throw new InvalidRequestException(b.toString());
					}
					Long valueOf;
					try {
						valueOf = translateForcedIdToPid(nextValue.getReference());
					} catch (ResourceNotFoundException e) {
						String resName = getContext().getResourceDefinition(type).getName();
						throw new InvalidRequestException("Resource " + resName + "/" + id + " not found, specified in path: " + nextPathsUnsplit);
					}
					ResourceTable target = getEntityManager().find(ResourceTable.class, valueOf);
					if (target == null) {
						String resName = getContext().getResourceDefinition(type).getName();
						throw new InvalidRequestException("Resource " + resName + "/" + id + " not found, specified in path: " + nextPathsUnsplit);
					}
					nextEntity = new ResourceLink(nextPathsUnsplit, theEntity, target);
				} else {
					if (!multiType) {
						throw new ConfigurationException("Search param " + nextSpDef.getName() + " is of unexpected datatype: " + nextObject.getClass());
					} else {
						continue;
					}
				}
				if (nextEntity != null) {
					retVal.add(nextEntity);
				}
			}
		}

		theEntity.setHasLinks(retVal.size() > 0);

		return retVal;
	}

	protected List<ResourceIndexedSearchParamDate> extractSearchParamDates(ResourceTable theEntity, IResource theResource) {
		return mySearchParamExtractor.extractSearchParamDates(theEntity, theResource);
	}

	protected ArrayList<ResourceIndexedSearchParamNumber> extractSearchParamNumber(ResourceTable theEntity, IResource theResource) {
		return mySearchParamExtractor.extractSearchParamNumber(theEntity, theResource);
	}

	protected List<ResourceIndexedSearchParamQuantity> extractSearchParamQuantity(ResourceTable theEntity, IResource theResource) {
		return mySearchParamExtractor.extractSearchParamQuantity(theEntity, theResource);
	}

	protected List<ResourceIndexedSearchParamString> extractSearchParamStrings(ResourceTable theEntity, IResource theResource) {
		return mySearchParamExtractor.extractSearchParamStrings(theEntity, theResource);
	}

	protected List<BaseResourceIndexedSearchParam> extractSearchParamTokens(ResourceTable theEntity, IResource theResource) {
		return mySearchParamExtractor.extractSearchParamTokens(theEntity, theResource);
	}

	private List<Object> extractValues(String thePaths, IResource theResource) {
		List<Object> values = new ArrayList<Object>();
		String[] nextPathsSplit = thePaths.split("\\|");
		FhirTerser t = getContext().newTerser();
		for (String nextPath : nextPathsSplit) {
			String nextPathTrimmed = nextPath.trim();
			try {
				values.addAll(t.getValues(theResource, nextPathTrimmed));
			} catch (Exception e) {
				RuntimeResourceDefinition def = getContext().getResourceDefinition(theResource);
				ourLog.warn("Failed to index values from path[{}] in resource type[{}]: ", nextPathTrimmed, def.getName(), e.toString());
			}
		}
		return values;
	}

	private void findMatchingTagIds(String theResourceName, IdDt theResourceId, Set<Long> tagIds, Class<? extends BaseTag> entityClass) {
		{
			CriteriaBuilder builder = getEntityManager().getCriteriaBuilder();
			CriteriaQuery<Tuple> cq = builder.createTupleQuery();
			Root<? extends BaseTag> from = cq.from(entityClass);
			cq.multiselect(from.get("myTagId").as(Long.class)).distinct(true);

			if (theResourceName != null) {
				Predicate typePredicate = builder.equal(from.get("myResourceType"), theResourceName);
				if (theResourceId != null) {
					cq.where(typePredicate, builder.equal(from.get("myResourceId"), translateForcedIdToPid(theResourceId)));
				} else {
					cq.where(typePredicate);
				}
			}

			TypedQuery<Tuple> query = getEntityManager().createQuery(cq);
			for (Tuple next : query.getResultList()) {
				tagIds.add(next.get(0, Long.class));
			}
		}
	}

	protected TagDefinition getTag(TagTypeEnum theTagType, String theScheme, String theTerm, String theLabel) {
		CriteriaBuilder builder = getEntityManager().getCriteriaBuilder();
		CriteriaQuery<TagDefinition> cq = builder.createQuery(TagDefinition.class);
		Root<TagDefinition> from = cq.from(TagDefinition.class);
		
		//@formatter:off
		if (isNotBlank(theScheme)) {
			cq.where(
				builder.and(
					builder.equal(from.get("myTagType"), theTagType), 
					builder.equal(from.get("mySystem"), theScheme), 
					builder.equal(from.get("myCode"), theTerm))
				);
		} else {
			cq.where(
				builder.and(
					builder.equal(from.get("myTagType"), theTagType), 
					builder.isNull(from.get("mySystem")), 
					builder.equal(from.get("myCode"), theTerm))
				);
		}
		//@formatter:on
		
		TypedQuery<TagDefinition> q = getEntityManager().createQuery(cq);
		try {
			return q.getSingleResult();
		} catch (NoResultException e) {
			TagDefinition retVal = new TagDefinition(theTagType, theScheme, theTerm, theLabel);
			getEntityManager().persist(retVal);
			return retVal;
		}
	}

	protected TagList getTags(Class<? extends IResource> theResourceType, IdDt theResourceId) {
		String resourceName = null;
		if (theResourceType != null) {
			resourceName = toResourceName(theResourceType);
			if (theResourceId != null && theResourceId.hasVersionIdPart()) {
				IFhirResourceDao<? extends IResource> dao = getDao(theResourceType);
				BaseHasResource entity = (BaseHasResource) dao.readEntity(theResourceId);//TODO unit test this CAST
				TagList retVal = new TagList();
				for (BaseTag next : entity.getTags()) {
					retVal.add(next.getTag().toTag());
				}
				return retVal;
			}
		}

		Set<Long> tagIds = new HashSet<Long>();
		findMatchingTagIds(resourceName, theResourceId, tagIds, ResourceTag.class);
		findMatchingTagIds(resourceName, theResourceId, tagIds, ResourceHistoryTag.class);
		if (tagIds.isEmpty()) {
			return new TagList();
		}
		{
			CriteriaBuilder builder = getEntityManager().getCriteriaBuilder();
			CriteriaQuery<TagDefinition> cq = builder.createQuery(TagDefinition.class);
			Root<TagDefinition> from = cq.from(TagDefinition.class);
			cq.where(from.get("myId").in(tagIds));
			cq.orderBy(builder.asc(from.get("mySystem")), builder.asc(from.get("myCode")));
			TypedQuery<TagDefinition> q = getEntityManager().createQuery(cq);
			q.setMaxResults(getConfig().getHardTagListLimit());

			TagList retVal = new TagList();
			for (TagDefinition next : q.getResultList()) {
				retVal.add(next.toTag());
			}

			return retVal;
		}
	}

	protected List<IBaseResource> loadResourcesById(Set<? extends IIdType> theIncludePids) {
		Set<Long> pids = new HashSet<Long>();
		for (IIdType next : theIncludePids) {
			if (next.isIdPartValidLong()) {
				pids.add(next.getIdPartAsLong());
			} else {
				try {
					pids.add(translateForcedIdToPid(next));
				} catch (ResourceNotFoundException e) {
					ourLog.warn("Failed to translate forced ID [{}] to PID", next.getValue());
				}
			}
		}

		if (pids.isEmpty()) {
			return new ArrayList<IBaseResource>();
		}
		
		CriteriaBuilder builder = getEntityManager().getCriteriaBuilder();
		CriteriaQuery<ResourceTable> cq = builder.createQuery(ResourceTable.class);
		Root<ResourceTable> from = cq.from(ResourceTable.class);
		// cq.where(builder.equal(from.get("myResourceType"),
		// getContext().getResourceDefinition(myResourceType).getName()));
		// if (theIncludePids != null) {
		cq.where(from.get("myId").in(pids));
		// }
		TypedQuery<ResourceTable> q = getEntityManager().createQuery(cq);

		ArrayList<IBaseResource> retVal = new ArrayList<IBaseResource>();
		for (ResourceTable next : q.getResultList()) {
			IResource resource = (IResource) next.getRelatedResource();
			retVal.add(resource);
		}

		return retVal;
	}

	protected void populateResourceIntoEntity(IResource theResource, ResourceTable theEntity) {

		if (theEntity.getPublished().isEmpty()) {
			theEntity.setPublished(new Date());
		}
		theEntity.setUpdated(new Date());

		theEntity.setResourceType(toResourceName(theResource));

		List<BaseResourceReferenceDt> refs = getContext().newTerser().getAllPopulatedChildElementsOfType(theResource, BaseResourceReferenceDt.class);
		for (BaseResourceReferenceDt nextRef : refs) {
			if (nextRef.getReference().isEmpty() == false) {
				if (nextRef.getReference().hasVersionIdPart()) {
					nextRef.setReference(nextRef.getReference().toUnqualifiedVersionless());
				}
			}
		}

		String encoded = myConfig.getResourceEncoding().newParser(getContext()).encodeResourceToString(theResource);
		ResourceEncodingEnum encoding = myConfig.getResourceEncoding();
		theEntity.setEncoding(encoding);
		theEntity.setFhirVersion(getContext().getVersion().getVersion());
		try {
			switch (encoding) {
			case JSON:
				theEntity.setResource(encoded.getBytes("UTF-8"));
				break;
			case JSONC:
				theEntity.setResource(GZipUtil.compress(encoded));
				break;
			}
		} catch (UnsupportedEncodingException e) {
			throw new InternalErrorException(e);
		}

		TagList tagList = ResourceMetadataKeyEnum.TAG_LIST.get(theResource);
		if (tagList != null) {
			for (Tag next : tagList) {
				TagDefinition tag = getTag(TagTypeEnum.TAG, next.getScheme(), next.getTerm(), next.getLabel());
				theEntity.addTag(tag);
				theEntity.setHasTags(true);
			}
		}

		List<BaseCodingDt> securityLabels = ResourceMetadataKeyEnum.SECURITY_LABELS.get(theResource);
		if (securityLabels != null) {
			for (BaseCodingDt next : securityLabels) {
				TagDefinition tag = getTag(TagTypeEnum.SECURITY_LABEL, next.getSystemElement().getValue(), next.getCodeElement().getValue(), next.getDisplayElement().getValue());
				theEntity.addTag(tag);
				theEntity.setHasTags(true);
			}
		}

		List<IdDt> profiles = ResourceMetadataKeyEnum.PROFILES.get(theResource);
		if (profiles != null) {
			for (IdDt next : profiles) {
				TagDefinition tag = getTag(TagTypeEnum.PROFILE, NS_JPA_PROFILE, next.getValue(), null);
				theEntity.addTag(tag);
				theEntity.setHasTags(true);
			}
		}

		String title = ResourceMetadataKeyEnum.TITLE.get(theResource);
		if (title != null && title.length() > BaseHasResource.MAX_TITLE_LENGTH) {
			title = title.substring(0, BaseHasResource.MAX_TITLE_LENGTH);
		}
		theEntity.setTitle(title);

	}


	

	protected ResourceTable toEntity(IResource theResource) {
		ResourceTable retVal = new ResourceTable();

		populateResourceIntoEntity(theResource, retVal);

		return retVal;
	}
	

	@Override
	public void setContext(FhirContext theContext) {
		super.setContext(theContext);
		switch (getContext().getVersion().getVersion()) {
		case DSTU1:
			mySearchParamExtractor = new SearchParamExtractorDstu1(theContext);
			break;
		case DSTU2:
			mySearchParamExtractor = new SearchParamExtractorDstu2(theContext);
			break;
		case DEV:
			throw new IllegalStateException("Don't know how to handle version: " + getContext().getVersion().getVersion());
		}
	}

	protected Long translateForcedIdToPid(IIdType theId) {
		if (isValidPid(theId)) {
			return theId.getIdPartAsLong();
		} else {
			TypedQuery<ForcedId> q = getEntityManager().createNamedQuery("Q_GET_FORCED_ID", ForcedId.class);
			q.setParameter("ID", theId.getIdPart());
			try {
				return q.getSingleResult().getResourcePid();
			} catch (NoResultException e) {
				throw new ResourceNotFoundException(theId);
			}
		}
	}

	protected String translatePidIdToForcedId(Long theId) {
		ForcedId forcedId = getEntityManager().find(ForcedId.class, theId);
		if (forcedId != null) {
			return forcedId.getForcedId();
		} else {
			return theId.toString();
		}
	}

	public ResourceTable updateEntity(final IResource theResource, ResourceTable entity, boolean theUpdateHistory, Date theDeletedTimestampOrNull) {
		return updateEntity(theResource, entity, theUpdateHistory, theDeletedTimestampOrNull, true, true);
	}

	public ResourceTable updateEntity(final IResource theResource, ResourceTable entity, boolean theUpdateHistory, Date theDeletedTimestampOrNull, boolean thePerformIndexing,
			boolean theUpdateVersion) {
		if (entity.getPublished() == null) {
			entity.setPublished(new Date());
		}

		if (theResource != null) {
			String resourceType = getContext().getResourceDefinition(theResource).getName();
			if (isNotBlank(entity.getResourceType()) && !entity.getResourceType().equals(resourceType)) {
				throw new UnprocessableEntityException("Existing resource ID[" + entity.getIdDt().toUnqualifiedVersionless() + "] is of type[" + entity.getResourceType() + "] - Cannot update with ["
						+ resourceType + "]");
			}
		}

		if (theUpdateHistory) {
			final ResourceHistoryTable historyEntry = entity.toHistory();
			getEntityManager().persist(historyEntry);
		}

		if (theUpdateVersion) {
			entity.setVersion(entity.getVersion() + 1);
		}

		Collection<ResourceIndexedSearchParamString> paramsString = new ArrayList<ResourceIndexedSearchParamString>(entity.getParamsString());
		Collection<ResourceIndexedSearchParamToken> paramsToken = new ArrayList<ResourceIndexedSearchParamToken>(entity.getParamsToken());
		Collection<ResourceIndexedSearchParamNumber> paramsNumber = new ArrayList<ResourceIndexedSearchParamNumber>(entity.getParamsNumber());
		Collection<ResourceIndexedSearchParamQuantity> paramsQuantity = new ArrayList<ResourceIndexedSearchParamQuantity>(entity.getParamsQuantity());
		Collection<ResourceIndexedSearchParamDate> paramsDate = new ArrayList<ResourceIndexedSearchParamDate>(entity.getParamsDate());
		Collection<ResourceLink> resourceLinks = new ArrayList<ResourceLink>(entity.getResourceLinks());

		List<ResourceIndexedSearchParamString> stringParams = null;
		List<ResourceIndexedSearchParamToken> tokenParams = null;
		List<ResourceIndexedSearchParamNumber> numberParams = null;
		List<ResourceIndexedSearchParamQuantity> quantityParams = null;
		List<ResourceIndexedSearchParamDate> dateParams = null;
		List<ResourceLink> links = null;

		if (theDeletedTimestampOrNull != null) {

			stringParams = Collections.emptyList();
			tokenParams = Collections.emptyList();
			numberParams = Collections.emptyList();
			quantityParams = Collections.emptyList();
			dateParams = Collections.emptyList();
			links = Collections.emptyList();
			entity.setDeleted(theDeletedTimestampOrNull);
			entity.setUpdated(theDeletedTimestampOrNull);

		} else {

			entity.setDeleted(null);

			if (thePerformIndexing) {

				stringParams = extractSearchParamStrings(entity, theResource);
				numberParams = extractSearchParamNumber(entity, theResource);
				quantityParams = extractSearchParamQuantity(entity, theResource);
				dateParams = extractSearchParamDates(entity, theResource);

//				ourLog.info("Indexing resource: {}", entity.getId());
				ourLog.trace("Storing string indexes: {}", stringParams);
				
				tokenParams = new ArrayList<ResourceIndexedSearchParamToken>();
				for (BaseResourceIndexedSearchParam next : extractSearchParamTokens(entity, theResource)) {
					if (next instanceof ResourceIndexedSearchParamToken) {
						tokenParams.add((ResourceIndexedSearchParamToken) next);
					} else {
						stringParams.add((ResourceIndexedSearchParamString) next);
					}
				}

				links = extractResourceLinks(entity, theResource);
				populateResourceIntoEntity(theResource, entity);
				entity.setUpdated(new Date());
				entity.setLanguage(theResource.getLanguage().getValue());
				entity.setParamsString(stringParams);
				entity.setParamsStringPopulated(stringParams.isEmpty() == false);
				entity.setParamsToken(tokenParams);
				entity.setParamsTokenPopulated(tokenParams.isEmpty() == false);
				entity.setParamsNumber(numberParams);
				entity.setParamsNumberPopulated(numberParams.isEmpty() == false);
				entity.setParamsQuantity(quantityParams);
				entity.setParamsQuantityPopulated(quantityParams.isEmpty() == false);
				entity.setParamsDate(dateParams);
				entity.setParamsDatePopulated(dateParams.isEmpty() == false);
				entity.setResourceLinks(links);
				entity.setHasLinks(links.isEmpty() == false);

			} else {

				populateResourceIntoEntity(theResource, entity);
				entity.setUpdated(new Date());
				entity.setLanguage(theResource.getLanguage().getValue());

			}

		}

		if (entity.getId() == null) {
			getEntityManager().persist(entity);

			if (entity.getForcedId() != null) {
				getEntityManager().persist(entity.getForcedId());
			}

		} else {
			entity = getEntityManager().merge(entity);
		}

		if (thePerformIndexing) {

			if (entity.isParamsStringPopulated()) {
				for (ResourceIndexedSearchParamString next : paramsString) {
					getEntityManager().remove(next);
				}
			}
			for (ResourceIndexedSearchParamString next : stringParams) {
				getEntityManager().persist(next);
			}

			if (entity.isParamsTokenPopulated()) {
				for (ResourceIndexedSearchParamToken next : paramsToken) {
					getEntityManager().remove(next);
				}
			}
			for (ResourceIndexedSearchParamToken next : tokenParams) {
				getEntityManager().persist(next);
			}

			if (entity.isParamsNumberPopulated()) {
				for (ResourceIndexedSearchParamNumber next : paramsNumber) {
					getEntityManager().remove(next);
				}
			}
			for (ResourceIndexedSearchParamNumber next : numberParams) {
				getEntityManager().persist(next);
			}

			if (entity.isParamsQuantityPopulated()) {
				for (ResourceIndexedSearchParamQuantity next : paramsQuantity) {
					getEntityManager().remove(next);
				}
			}
			for (ResourceIndexedSearchParamQuantity next : quantityParams) {
				getEntityManager().persist(next);
			}

			if (entity.isParamsDatePopulated()) {
				for (ResourceIndexedSearchParamDate next : paramsDate) {
					getEntityManager().remove(next);
				}
			}
			for (ResourceIndexedSearchParamDate next : dateParams) {
				getEntityManager().persist(next);
			}

			if (entity.isHasLinks()) {
				for (ResourceLink next : resourceLinks) {
					getEntityManager().remove(next);
				}
			}
			for (ResourceLink next : links) {
				getEntityManager().persist(next);
			}

		} // if thePerformIndexing

		getEntityManager().flush();

		if (theResource != null) {
			theResource.setId(entity.getIdDt());
		}

		return entity;
	}


}
