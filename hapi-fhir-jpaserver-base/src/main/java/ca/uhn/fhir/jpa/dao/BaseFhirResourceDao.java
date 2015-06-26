package ca.uhn.fhir.jpa.dao;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContextType;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import net.vidageek.mirror.dsl.Mirror;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.jpa.entity.BaseResourceEntity;
import ca.uhn.fhir.jpa.entity.IResourceEntity;
import ca.uhn.fhir.jpa.entity.ResourceIndexedSearchParamString;
import ca.uhn.fhir.jpa.entity.ResourceTable;
import ca.uhn.fhir.jpa.entity.TagTypeEnum;
import ca.uhn.fhir.jpa.util.StopWatch;
import ca.uhn.fhir.model.api.IPrimitiveDatatype;
import ca.uhn.fhir.model.api.IQueryParameterType;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.api.Include;
import ca.uhn.fhir.model.api.ResourceMetadataKeyEnum;
import ca.uhn.fhir.model.api.TagList;
import ca.uhn.fhir.model.base.composite.BaseResourceReferenceDt;
import ca.uhn.fhir.model.dstu2.composite.MetaDt;
import ca.uhn.fhir.model.dstu2.resource.OperationOutcome;
import ca.uhn.fhir.model.dstu2.valueset.IssueSeverityEnum;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.model.primitive.InstantDt;
import ca.uhn.fhir.model.valueset.BundleEntrySearchModeEnum;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IBundleProvider;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.util.FhirTerser;

/**
 * This class serves as Template with commmon dao functions that are meant to be extended by subclasses.
 * @author Ismael Sarmento
 */
@Transactional(propagation = Propagation.REQUIRED)
public abstract class BaseFhirResourceDao<T extends IResource> implements IFhirResourceDao<T>{
	
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(BaseFhirResourceDao.class);
	
	@PersistenceContext(type = PersistenceContextType.TRANSACTION)
	private EntityManager myEntityManager;
	
	private BaseFhirDao baseFhirDao;
	
	@Autowired
	private PlatformTransactionManager myPlatformTransactionManager;
	
	private Class<T> myResourceType;
	private Class<? extends BaseResourceEntity> myResourceEntity;

	@Override
	public Class<T> getResourceType() {
		return myResourceType;
	}
	
	@SuppressWarnings("unchecked")
	@Required
	public void setResourceType(Class<? extends IResource> theTableType) {
		myResourceType = (Class<T>) theTableType;
	}

	public Class<? extends BaseResourceEntity> getResourceEntity() {
		return myResourceEntity;
	}

	public void setResourceEntity(Class<? extends BaseResourceEntity> theResourceEntity) {
		this.myResourceEntity = theResourceEntity;
	}
	
	/*
	 ****************************** 
	 * METHODS WITH STRUCTURE OF ca.uhn.BaseFhirResourceDao
	 *****************************/
	
	@Override
	public DaoMethodOutcome create(final T theResource) {
		return create(theResource, null, true);
	}

	@Override
	public DaoMethodOutcome create(final T theResource, String theIfNoneExist) {
		return create(theResource, theIfNoneExist, true);
	}

	@Override
	public DaoMethodOutcome create(T theResource, String theIfNoneExist, boolean thePerformIndexing) {
		if (isNotBlank(theResource.getId().getIdPart())) {
			throw new InvalidRequestException(baseFhirDao.getContext().getLocalizer().getMessage(BaseFhirResourceDao.class, "failedToCreateWithClientAssignedId", theResource.getId().getIdPart()));
		}

		return doCreate(theResource, theIfNoneExist, thePerformIndexing);
	}
	
	private DaoMethodOutcome doCreate(T theResource, String theIfNoneExist, boolean thePerformIndexing) {
		StopWatch w = new StopWatch();
		BaseResourceEntity entity = new Mirror().on(myResourceEntity).invoke().constructor().withoutArgs();
		entity.constructEntityFromResource(theResource);

		if (isNotBlank(theIfNoneExist)) {//FIXME remove this if not needed
			Set<Long> match = baseFhirDao.processMatchUrl(theIfNoneExist, myResourceType);
			if (match.size() > 1) {
				String msg = baseFhirDao.getContext().getLocalizer().getMessage(BaseFhirDao.class, "transactionOperationWithMultipleMatchFailure", "CREATE", theIfNoneExist, match.size());
				throw new PreconditionFailedException(msg);
			} else if (match.size() == 1) {
				Long pid = match.iterator().next();
				entity = myEntityManager.find(ResourceTable.class, pid);
				return toMethodOutcome(entity, theResource).setCreated(false);
			}
		}

		baseFhirDao.updateEntity(theResource, entity, false, null, thePerformIndexing, true);

		DaoMethodOutcome outcome = toMethodOutcome(entity, theResource).setCreated(true);

		baseFhirDao.notifyWriteCompleted();
		ourLog.info("Processed create on {} in {}ms", myResourceType, w.getMillisAndRestart());//WARNING once it was resource name
		return outcome;
	}
	
	private DaoMethodOutcome toMethodOutcome(final BaseResourceEntity entity, IResource theResource) {
		DaoMethodOutcome outcome = new DaoMethodOutcome();
		outcome.setId(new IdDt(entity.getId()));
		outcome.setEntity(entity);
		outcome.setResource(theResource);
		if (theResource != null) {
			theResource.setId(new IdDt(entity.getId()));
		}
		return outcome;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public T read(IdDt theId) {
//		validateResourceTypeAndThrowIllegalArgumentException(theId);

		StopWatch w = new StopWatch();
		BaseResourceEntity entity = (BaseResourceEntity) readEntity(theId);
//		validateResourceType(entity);

		T retVal = (T) entity.getRelatedResource();//toResource(myResourceType, entity);

		InstantDt deleted = ResourceMetadataKeyEnum.DELETED_AT.get(retVal);
		if (deleted != null && !deleted.isEmpty()) {
			throw new ResourceGoneException("Resource was deleted at " + deleted.getValueAsString());
		}

		ourLog.info("Processed read on {} in {}ms", theId.getValue(), w.getMillisAndRestart());
		return retVal;
	}
	
	//	private void validateResourceType(BaseHapiResourceTable entity) {
	//		if (!myResourceName.equals(entity.getResourceType())) {
	//			throw new ResourceNotFoundException("Resource with ID " + entity.getIdDt().getIdPart() + " exists but it is not of type " + myResourceName + ", found resource of type "
	//					+ entity.getResourceType());
	//		}
	//	}
	//	
	//	private void validateResourceTypeAndThrowIllegalArgumentException(IdDt theId) {
	//		if (theId.hasResourceType() && !theId.getResourceType().equals(myResourceName)) {
	//			throw new IllegalArgumentException("Incorrect resource type (" + theId.getResourceType() + ") for this DAO, wanted: " + myResourceName);
	//		}
	//	}


	@Override
	public BaseResourceEntity readEntity(IdDt theId) {
		boolean checkForForcedId = true;
	
		BaseResourceEntity entity = readEntity(theId, checkForForcedId);
	
		return entity;
	}
	
	@Override
	public BaseResourceEntity readEntity(IdDt theId, boolean theCheckForForcedId) {
		//validateResourceTypeAndThrowIllegalArgumentException(theId);
	
		Long pid = theId.getIdPartAsLong();//translateForcedIdToPid(theId); //WARNING ForcedId strategy 
		BaseResourceEntity entity = myEntityManager.find(getResourceEntity(), pid);
	//	if (theId.hasVersionIdPart()) { //FIXME implement the versioning check
	//		if (entity.getVersion() != theId.getVersionIdPartAsLong()) {
	//			entity = null;
	//		}
	//	}
	//
	//	if (entity == null) {
	//		if (theId.hasVersionIdPart()) {
	//			TypedQuery<ResourceHistoryTable> q = myEntityManager.createQuery(
	//					"SELECT t from ResourceHistoryTable t WHERE t.myResourceId = :RID AND t.myResourceType = :RTYP AND t.myResourceVersion = :RVER", ResourceHistoryTable.class);
	//			q.setParameter("RID", pid);
	//			q.setParameter("RTYP", myResourceType);//WARNING originally myResourceName
	//			q.setParameter("RVER", theId.getVersionIdPartAsLong());
	//			entity = q.getSingleResult();
	//		}
	//		if (entity == null) {
	//			throw new ResourceNotFoundException(theId);
	//		}
	//	}
	
		//validateResourceType(entity);
	
		if (theCheckForForcedId) {
			//validateGivenIdIsAppropriateToRetrieveResource(theId, entity);//WARNING checks for forcedId
		}
		return entity;
	}
	
	
	@Override
	public IBundleProvider search(Map<String, IQueryParameterType> theParams) {
		SearchParameterMap map = new SearchParameterMap();
		for (Entry<String, IQueryParameterType> nextEntry : theParams.entrySet()) {
			map.add(nextEntry.getKey(), (nextEntry.getValue()));
		}
		return search(map);
	}
	
	public IBundleProvider search(String theParameterName, IQueryParameterType theValue) {
		return search(Collections.singletonMap(theParameterName, theValue));
	}

	@Override
	public Set<Long> searchForIds(Map<String, IQueryParameterType> theParams) {
		SearchParameterMap map = new SearchParameterMap();
		for (Entry<String, IQueryParameterType> nextEntry : theParams.entrySet()) {
			map.add(nextEntry.getKey(), (nextEntry.getValue()));
		}
		return searchForIdsWithAndOr(map);
	}

	@Override
	public Set<Long> searchForIds(String theParameterName, IQueryParameterType theValue) {
		return searchForIds(Collections.singletonMap(theParameterName, theValue));
	}
	
	@Override
	public IBundleProvider search(final SearchParameterMap theParams) {
		StopWatch w = new StopWatch();
		final InstantDt now = InstantDt.withCurrentTime();

		Set<Long> loadPids;
		if (theParams.isEmpty()) {
			CriteriaBuilder builder = myEntityManager.getCriteriaBuilder();
			CriteriaQuery<Long> criteria = builder.createQuery(Long.class);
			Root<? extends BaseResourceEntity> from = criteria.from(getResourceEntity());
			criteria.select(from.get("id").as(Long.class));
			List<Long> resultList = myEntityManager.createQuery(criteria).getResultList();
			loadPids = new HashSet<Long>(resultList);
		} else {
			loadPids = searchForIdsWithAndOr(theParams);
			if (loadPids.isEmpty()) {
				return new SimpleBundleProvider();
			}
		}

		final List<Long> pids= new ArrayList<Long>(loadPids);
		//TODO removed sort excerpt
		
		// Load _revinclude resources
		if (theParams.getRevIncludes() != null && theParams.getRevIncludes().isEmpty() == false) {
			//loadReverseIncludes(pids, theParams.getRevIncludes()); //FIXME Fix loadReverseIncludes method
		}

		IBundleProvider retVal = new IBundleProvider() {
			@Override
			public InstantDt getPublished() {
				return now;
			}

			@Override
			public List<IBaseResource> getResources(final int theFromIndex, final int theToIndex) {
				TransactionTemplate template = new TransactionTemplate(myPlatformTransactionManager);
				return template.execute(new TransactionCallback<List<IBaseResource>>() {
					@Override
					public List<IBaseResource> doInTransaction(TransactionStatus theStatus) {
						List<Long> pidsSubList = pids.subList(theFromIndex, theToIndex);

						// Execute the query and make sure we return distinct results
						List<IBaseResource> retVal = new ArrayList<IBaseResource>();
						loadResourcesByPid(pidsSubList, retVal, BundleEntrySearchModeEnum.MATCH);

						/*
						 * Load _include resources - Note that _revincludes are handled differently than _include ones, as they are counted towards the total count and paged, so they are loaded
						 * outside the bundle provider
						 */
						if (theParams.getIncludes() != null && theParams.getIncludes().isEmpty() == false) {
							Set<IIdType> previouslyLoadedPids = new HashSet<IIdType>();
							for (IBaseResource next : retVal) {
								previouslyLoadedPids.add(next.getIdElement().toUnqualifiedVersionless());
							}

							Set<IdDt> includePids = new HashSet<IdDt>();
							List<IBaseResource> resources = retVal;
							do {
								includePids.clear();

								FhirTerser t = baseFhirDao.getContext().newTerser();
								for (Include next : theParams.getIncludes()) {
									for (IBaseResource nextResource : resources) {
										RuntimeResourceDefinition def = baseFhirDao.getContext().getResourceDefinition(nextResource);
										List<Object> values = null;
										switch (baseFhirDao.getContext().getVersion().getVersion()) {
										case DSTU1:
											if ("*".equals(next.getValue())) {
												values = new ArrayList<Object>();
												values.addAll(t.getAllPopulatedChildElementsOfType(nextResource, BaseResourceReferenceDt.class));
											} else if (next.getValue().startsWith(def.getName() + ".")) {
												values = t.getValues(nextResource, next.getValue());
											} else {
												values = Collections.emptyList();
											}
											break;
										case DSTU2:
											if ("*".equals(next.getValue())) {
												values = new ArrayList<Object>();
												values.addAll(t.getAllPopulatedChildElementsOfType(nextResource, BaseResourceReferenceDt.class));
											} else if (next.getValue().startsWith(def.getName() + ":")) {
												values = new ArrayList<Object>();
												RuntimeSearchParam sp = def.getSearchParam(next.getValue().substring(next.getValue().indexOf(':')+1));
												for (String nextPath : sp.getPathsSplit()) {
													values.addAll(t.getValues(nextResource, nextPath));
												}
											} else {
												values = Collections.emptyList();
											}
											break;
										case DEV:
											break;
										case DSTU2_HL7ORG:
											break;
										default:
											break;
										}
										if(values == null)
											throw new IllegalStateException("Support for Search not provided for Version: " + baseFhirDao.getContext().getVersion().getVersion());

										for (Object object : values) {
											if (object == null) {
												continue;
											}
											if (!(object instanceof BaseResourceReferenceDt)) {
												throw new InvalidRequestException("Path '" + next.getValue() + "' produced non ResourceReferenceDt value: " + object.getClass());
											}
											BaseResourceReferenceDt rr = (BaseResourceReferenceDt) object;
											if (rr.getReference().isEmpty()) {
												continue;
											}
											if (rr.getReference().isLocal()) {
												continue;
											}

											IdDt nextId = rr.getReference().toUnqualified();
											if (!previouslyLoadedPids.contains(nextId)) {
												includePids.add(nextId);
												previouslyLoadedPids.add(nextId);
											}
										}
									}
								}

								resources = addResourcesAsIncludesById(retVal, includePids, resources);
							} while (includePids.size() > 0 && previouslyLoadedPids.size() < baseFhirDao.getConfig().getIncludeLimit());

							if (previouslyLoadedPids.size() >= baseFhirDao.getConfig().getIncludeLimit()) {
								OperationOutcome oo = new OperationOutcome();
								oo.addIssue().setSeverity(IssueSeverityEnum.WARNING)
										.setDetails("Not all _include resources were actually included as the request surpassed the limit of " + baseFhirDao.getConfig().getIncludeLimit() + " resources");
								retVal.add(0, oo);
							}
						}

						return retVal;
					}

				});
			}

			@Override
			public Integer preferredPageSize() {
				return theParams.getCount();
			}

			@Override
			public int size() {
				return pids.size();
			}
		};

		ourLog.info("Processed search for {} on {} in {}ms", new Object[] { getResourceType(), theParams, w.getMillisAndRestart() });

		return retVal;
	}
	
	@Override
	public Set<Long> searchForIdsWithAndOr(SearchParameterMap theParams) {
		SearchParameterMap params = theParams;
		if (params == null) {
			params = new SearchParameterMap();
		}

		RuntimeResourceDefinition resourceDef = baseFhirDao.getContext().getResourceDefinition(myResourceType);

		Set<Long> pids = new HashSet<Long>();

		for (Entry<String, List<List<? extends IQueryParameterType>>> nextParamEntry : params.entrySet()) {
			String nextParamName = nextParamEntry.getKey();
			if (nextParamName.equals("_id")) {

				if (nextParamEntry.getValue().isEmpty()) {
					continue;
				} else if (nextParamEntry.getValue().size() > 1) {
					throw new InvalidRequestException("AND queries not supported for _id (Multiple instances of this param found)");
				} else {
					Set<Long> joinPids = new HashSet<Long>();
					List<? extends IQueryParameterType> nextValue = nextParamEntry.getValue().get(0);
					if (nextValue == null || nextValue.size() == 0) {
						continue;
					} else {
						for (IQueryParameterType next : nextValue) {
							String value = next.getValueAsQueryToken();
							IdDt valueId = new IdDt(value);
							try {
								if (baseFhirDao.isValidPid(valueId)) {
									long valueLong =  valueId.getIdPartAsLong();
									//translateForcedIdToPid(valueId); //WARNING altered line
									joinPids.add(valueLong);
								}
							} catch (ResourceNotFoundException e) {
								// This isn't an error, just means no result found
							}
						}
						if (joinPids.isEmpty()) {
							continue;
						}
					}

					pids = addPredicateId(pids, joinPids);
					if (pids.isEmpty()) {
						return new HashSet<Long>();
					}

//					if (pids.isEmpty()) {
//						pids.addAll(joinPids);
//					} else {
						pids.retainAll(joinPids);
//					}
				}

			} 
			else if (nextParamName.equals("_language")) {

				//pids = addPredicateLanguage(pids, nextParamEntry.getValue());

			} else {

				RuntimeSearchParam nextParamDef = resourceDef.getSearchParam(nextParamName);
				if (nextParamDef != null) {
					switch (nextParamDef.getParamType()) {
					case DATE:
						for (List<? extends IQueryParameterType> nextAnd : nextParamEntry.getValue()) {
							pids = addPredicateDate(nextParamName, pids, nextAnd);
							if (pids.isEmpty()) {
								return new HashSet<Long>();
							}
						}
						break;
					case QUANTITY:
						for (List<? extends IQueryParameterType> nextAnd : nextParamEntry.getValue()) {
							//pids = addPredicateQuantity(nextParamName, pids, nextAnd);
							if (pids.isEmpty()) {
								return new HashSet<Long>();
							}
						}
						break;
					case REFERENCE:
						for (List<? extends IQueryParameterType> nextAnd : nextParamEntry.getValue()) {
							//pids = addPredicateReference(nextParamName, pids, nextAnd);
							if (pids.isEmpty()) {
								return new HashSet<Long>();
							}
						}
						break;
					case STRING:
						for (List<? extends IQueryParameterType> nextAnd : nextParamEntry.getValue()) {
							pids = addPredicateString(nextParamName, pids, nextAnd);
							if (pids.isEmpty()) {
								return new HashSet<Long>();
							}
						}
						break;
					case TOKEN:
						for (List<? extends IQueryParameterType> nextAnd : nextParamEntry.getValue()) {
							//pids = addPredicateToken(nextParamName, pids, nextAnd);
							if (pids.isEmpty()) {
								return new HashSet<Long>();
							}
						}
						break;
					case NUMBER:
						for (List<? extends IQueryParameterType> nextAnd : nextParamEntry.getValue()) {
							//pids = addPredicateNumber(nextParamName, pids, nextAnd);
							if (pids.isEmpty()) {
								return new HashSet<Long>();
							}
						}
						break;
					case COMPOSITE:
						for (List<? extends IQueryParameterType> nextAnd : nextParamEntry.getValue()) {
							//pids = addPredicateComposite(nextParamDef, pids, nextAnd);
							if (pids.isEmpty()) {
								return new HashSet<Long>();
							}
						}
						break;
					default:
						break;
					}
				}
			}
		}

		return pids;
	}
	
	private void loadResourcesByPid(Collection<Long> theIncludePids, List<IBaseResource> theResourceListToPopulate, BundleEntrySearchModeEnum theBundleEntryStatus) {
		if (theIncludePids.isEmpty()) {
			return;
		}

		Map<Long, Integer> position = new HashMap<Long, Integer>();
		for (Long next : theIncludePids) {
			position.put(next, theResourceListToPopulate.size());
			theResourceListToPopulate.add(null);
		}

		CriteriaBuilder builder = myEntityManager.getCriteriaBuilder();
		@SuppressWarnings("unchecked")
		CriteriaQuery<BaseResourceEntity> cq = (CriteriaQuery<BaseResourceEntity>) builder.createQuery(getResourceEntity());
		@SuppressWarnings("unchecked")
		Root<BaseResourceEntity> from = (Root<BaseResourceEntity>) cq.from(getResourceEntity());
		cq.select(from);
		cq.where(from.get("id").in(theIncludePids));
		TypedQuery<BaseResourceEntity> q = myEntityManager.createQuery(cq);

		for (BaseResourceEntity entity : q.getResultList()) { 
			//Class<? extends IBaseResource> resourceType = baseFhirDao.getContext().getResourceDefinition(next.getResourceType()).getImplementingClass();
			IResource resource = entity.getRelatedResource();
			Integer index = position.get(entity.getId());
			if (index == null) {
				ourLog.warn("Got back unexpected resource PID {}", entity.getId());
				continue;
			}

			ResourceMetadataKeyEnum.ENTRY_SEARCH_MODE.put(resource, theBundleEntryStatus);

			theResourceListToPopulate.set(index, resource);
		}
	}
	
	private List<IBaseResource> addResourcesAsIncludesById(List<IBaseResource> theListToPopulate, Set<? extends IIdType> includePids, List<IBaseResource> resources) {
		if (!includePids.isEmpty()) {
			ourLog.info("Loading {} included resources", includePids.size());
			Set<Long> pids = new HashSet<Long>();
			for (IIdType next : includePids) {
				if (next.isIdPartValidLong()) {
					pids.add(next.getIdPartAsLong());
				} 
			}

			if (pids.isEmpty()) {
				return new ArrayList<IBaseResource>();
			}
			
			CriteriaBuilder builder = baseFhirDao.getEntityManager().getCriteriaBuilder();
			CriteriaQuery<? extends BaseResourceEntity> cq = builder.createQuery(myResourceEntity);
			Root<? extends BaseResourceEntity> from = cq.from(myResourceEntity);
			cq.where(from.get("id").in(pids));
			TypedQuery<? extends BaseResourceEntity> q = baseFhirDao.getEntityManager().createQuery(cq); 

			//FIXME after search, transform to resource
//			for (BaseResourceEntity next : q.getResultList()) {
//				IResource resource = (IResource) baseFhirDao.toResource(next);
//				resources.add(resource);
//			}

			for (IBaseResource next : resources) {
				ResourceMetadataKeyEnum.ENTRY_SEARCH_MODE.put((IResource) next, BundleEntrySearchModeEnum.INCLUDE);
			}
			theListToPopulate.addAll(resources);
		}
		return resources;
	}
	
	
	
	/*
	 * **********************
	 * PREDICATES
	 * **********************
	 */
	protected Set<Long> addPredicateId(Set<Long> theExistingPids, Set<Long> thePids) {
		if (thePids == null || thePids.isEmpty()) {
			return Collections.emptySet();
		}

		CriteriaBuilder builder = myEntityManager.getCriteriaBuilder();
		CriteriaQuery<Long> cq = builder.createQuery(Long.class);
		Root<? extends BaseResourceEntity> from = cq.from(getResourceEntity());
		cq.select(from.get("id").as(Long.class)); 
		Predicate idPrecidate = from.get("id").in(thePids);
		cq.where(idPrecidate);
		TypedQuery<Long> q = myEntityManager.createQuery(cq);
		HashSet<Long> found = new HashSet<Long>(q.getResultList());
		if (!theExistingPids.isEmpty()) {
			theExistingPids.retainAll(found);
		}

		return found;
	}
	
	protected Set<Long> addPredicateDate(String theParamName, Set<Long> thePids, List<? extends IQueryParameterType> theList) {
		if (theList == null || theList.isEmpty()) {
			return thePids;
		}

//		if (Boolean.TRUE.equals(theList.get(0).getMissing())) {
//			return addPredicateParamMissing(thePids, "myParamsDate", theParamName, ResourceIndexedSearchParamDate.class);
//		}

		CriteriaBuilder builder = myEntityManager.getCriteriaBuilder();
		CriteriaQuery<Long> cq = builder.createQuery(Long.class);
		Root<? extends BaseResourceEntity> from = cq.from(getResourceEntity());
		cq.select(from.get("id").as(Long.class));
		
//		Root<ResourceIndexedSearchParamDate> from = cq.from(ResourceIndexedSearchParamDate.class);
//		cq.select(from.get("myResourcePid").as(Long.class));
//
		List<Predicate> codePredicates = new ArrayList<Predicate>();
		for (IQueryParameterType nextOr : theList) {
			
//			if (addPredicateMissingFalseIfPresent(builder, theParamName, from, codePredicates, nextOr)) {//WARNING check his line for correctness
//				continue;
//			}

			IQueryParameterType params = nextOr;
			Predicate p = createPredicateDate(builder, from, theParamName, params);
			codePredicates.add(p);
		}

		Predicate masterCodePredicate = builder.or(codePredicates.toArray(new Predicate[0]));

//		Predicate type = builder.equal(from.get("myResourceType"), myResourceType);
//		Predicate name = builder.equal(from.get("myParamName"), theParamName);
		if (thePids.size() > 0) {
			Predicate inPids = (from.get("id").in(thePids));//WARNING included previously 'name' 'type' and 'masterCodePredicate'
			cq.where(builder.and(inPids, masterCodePredicate));
		} else {
			cq.where(builder.and(masterCodePredicate));
		}

		TypedQuery<Long> q = myEntityManager.createQuery(cq);
		return new HashSet<Long>(q.getResultList());
	}
	
	protected Predicate createPredicateDate(CriteriaBuilder theBuilder, Root<? extends IResourceEntity> from, String theParamName, IQueryParameterType theParam) {
		Predicate p;
		if (theParam instanceof DateParam) {
			DateParam date = (DateParam) theParam;
			if (!date.isEmpty()) {
				DateRangeParam range = new DateRangeParam(date);
				p = createPredicateDateFromRange(theBuilder, from, range, theParamName, theParam);
			} else {
				// From original method: TODO: handle missing date param?
				p = null;
			}
		} else if (theParam instanceof DateRangeParam) {
			DateRangeParam range = (DateRangeParam) theParam;
			p = createPredicateDateFromRange(theBuilder, from, range, theParamName, theParam);
		} else {
			throw new IllegalArgumentException("Invalid token type: " + theParam.getClass());
		}
		return p;
	}
	
	protected Predicate createPredicateDateFromRange(CriteriaBuilder theBuilder, Root<? extends IResourceEntity> from, DateRangeParam theRange, String theParamName, IQueryParameterType theParam) {
		Date lowerBound = theRange.getLowerBoundAsInstant();
		Date upperBound = theRange.getUpperBoundAsInstant();

		Predicate lb = null;
		if (lowerBound != null) {
			lb = translatePredicateDateGreaterThan(theParamName, lowerBound, from, theBuilder); 
		}

		Predicate ub = null;
		if (upperBound != null) {
			ub = translatePredicateDateLessThan(theParamName, upperBound, from, theBuilder); 
		}

		if (lb != null && ub != null) {
			return (theBuilder.and(lb, ub));
		} else if (lb != null) {
			return (lb);
		} else {
			return (ub);
		}
	}
	
	private Predicate createPredicateString(IQueryParameterType theParameter, String theParamName, CriteriaBuilder theBuilder,
			Root<? extends BaseResourceEntity> from) {
		String rawSearchTerm;
		if (theParameter instanceof TokenParam) {
			TokenParam id = (TokenParam) theParameter;
			if (!id.isText()) {
				throw new IllegalStateException("Trying to process a text search on a non-text token parameter");
			}
			rawSearchTerm = id.getValue();
		} else if (theParameter instanceof StringParam) {
			StringParam id = (StringParam) theParameter;
			rawSearchTerm = id.getValue();
		} else if (theParameter instanceof IPrimitiveDatatype<?>) {
			IPrimitiveDatatype<?> id = (IPrimitiveDatatype<?>) theParameter;
			rawSearchTerm = id.getValueAsString();
		} else {
			throw new IllegalArgumentException("Invalid token type: " + theParameter.getClass());
		}

		if (rawSearchTerm.length() > ResourceIndexedSearchParamString.MAX_LENGTH) {
			throw new InvalidRequestException("Parameter[" + theParamName + "] has length (" + rawSearchTerm.length() + ") that is longer than maximum allowed ("
					+ ResourceIndexedSearchParamString.MAX_LENGTH + "): " + rawSearchTerm);
		}

		String likeExpression = BaseFhirDao.normalizeString(rawSearchTerm);
		likeExpression = likeExpression.replace("%", "[%]") + "%";
		Predicate singleCode = translatePredicateString(theParamName, likeExpression, from, theBuilder);
//		Predicate singleCode = theBuilder.like(from.get("myValueNormalized").as(String.class), likeExpression);
//		Predicate singleCode = theBuilder.like(from.get("myValueNormalized").as(String.class), likeExpression);
//		if (theParameter instanceof StringParam && ((StringParam) theParameter).isExact()) {
//			Predicate exactCode = theBuilder.equal(from.get("myValueExact"), rawSearchTerm);
//			singleCode = theBuilder.and(singleCode, exactCode);
//		}
		return singleCode;
	}
	
	//THese vary according to each entity and its attributes (search params)
	public abstract Predicate translatePredicateString(String theParamName, String likeExpression, Root<? extends IResourceEntity> from, CriteriaBuilder theBuilder);
	public abstract Predicate translatePredicateDateLessThan(String theParamName, Date upperBound, Root<? extends IResourceEntity> from, CriteriaBuilder theBuilder);
	public abstract Predicate translatePredicateDateGreaterThan(String theParamName, Date lowerBound, Root<? extends IResourceEntity> from, CriteriaBuilder theBuilder);
	
	private Set<Long> addPredicateString(String theParamName, Set<Long> thePids, List<? extends IQueryParameterType> theList) {
		if (theList == null || theList.isEmpty()) {
			return thePids;
		}

		if (Boolean.TRUE.equals(theList.get(0).getMissing())) {
//			return addPredicateParamMissing(thePids, "myParamsString", theParamName, getResourceTable());
			System.err.println(); //WARNING
		}

		CriteriaBuilder builder = myEntityManager.getCriteriaBuilder();
		CriteriaQuery<Long> cq = builder.createQuery(Long.class);
		Root<? extends BaseResourceEntity> from = cq.from(getResourceEntity());
		cq.select(from.get("id").as(Long.class));

		List<Predicate> codePredicates = new ArrayList<Predicate>();
		for (IQueryParameterType nextOr : theList) {
			IQueryParameterType theParameter = nextOr;
//			if (addPredicateMissingFalseIfPresent(builder, theParamName, from, codePredicates, nextOr)) {//WARNING check his line for correctness
//				continue;
//			}
			
			Predicate singleCode = createPredicateString(theParameter, theParamName, builder, from);
			codePredicates.add(singleCode);
		}

		Predicate masterCodePredicate = builder.or(codePredicates.toArray(new Predicate[0]));

//		Predicate type = builder.equal(from.get("myResourceType"), myResourceName);
//		Predicate name = builder.equal(from.get("myParamName"), theParamName);
		if (thePids.size() > 0) {
			Predicate inPids = (from.get("myResourcePid").in(thePids));
			cq.where(builder.and(//type, name, 
					masterCodePredicate, inPids));
		} else {
			cq.where(builder.and(//type, name, 
					masterCodePredicate));
		}

		TypedQuery<Long> q = myEntityManager.createQuery(cq);
		return new HashSet<Long>(q.getResultList());
	}
	
//	protected Set<Long> addPredicateParamMissing(Set<Long> thePids, String joinName, String theParamName, Class<? extends BaseResourceIndexedSearchParam> theParamTable) {
//		String resourceType = baseFhirDao.getContext().getResourceDefinition(getResourceType()).getName();
//
//		CriteriaBuilder builder = myEntityManager.getCriteriaBuilder();
//		CriteriaQuery<Long> cq = builder.createQuery(Long.class);
//		Root<ResourceTable> from = cq.from(ResourceTable.class);
//		cq.select(from.get("myId").as(Long.class));
//
//		Subquery<Long> subQ = cq.subquery(Long.class);
//		Root<? extends BaseResourceIndexedSearchParam> subQfrom = subQ.from(theParamTable); 
//		subQ.select(subQfrom.get("myResourcePid").as(Long.class));
//		Predicate subQname = builder.equal(subQfrom.get("myParamName"), theParamName);
//		Predicate subQtype = builder.equal(subQfrom.get("myResourceType"), resourceType);
//		subQ.where(builder.and(subQtype, subQname));
//
//		Predicate joinPredicate = builder.not(builder.in(from.get("myId")).value(subQ));
//		Predicate typePredicate = builder.equal(from.get("myResourceType"), resourceType);
//		
//		if (thePids.size() > 0) {
//			Predicate inPids = (from.get("myId").in(thePids));
//			cq.where(builder.and(inPids, typePredicate, joinPredicate));
//		} else {
//			cq.where(builder.and(typePredicate, joinPredicate));
//		}
//		
//		ourLog.info("Adding :missing qualifier for parameter '{}'", theParamName);
//		
//		TypedQuery<Long> q = myEntityManager.createQuery(cq);
//		List<Long> resultList = q.getResultList();
//		HashSet<Long> retVal = new HashSet<Long>(resultList);
//		return retVal;
//	}
	
	public BaseFhirDao getBaseFhirDao() {
		return baseFhirDao;
	}

	public void setBaseFhirDao(BaseFhirDao baseFhirDao) {
		this.baseFhirDao = baseFhirDao;
	}

	@Override
	public void registerDaoListener(IDaoListener theListener) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void addTag(IdDt theId, TagTypeEnum theTagType, String theScheme,
			String theTerm, String theLabel) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public DaoMethodOutcome delete(IdDt theResource) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public DaoMethodOutcome deleteByUrl(String theString) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public TagList getAllResourceTags() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public TagList getTags(IdDt theResourceId) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public IBundleProvider history(Date theSince) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public IBundleProvider history(IdDt theId, Date theSince) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public IBundleProvider history(Long theId, Date theSince) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void removeTag(IdDt theId, TagTypeEnum theTagType, String theScheme,
			String theTerm) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public DaoMethodOutcome update(T theResource) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public DaoMethodOutcome update(T theResource, String theMatchUrl) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public DaoMethodOutcome update(T theResource, String theMatchUrl,
			boolean thePerformIndexing) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public MetaDt metaGetOperation() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public MetaDt metaGetOperation(IdDt theId) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public MetaDt metaDeleteOperation(IdDt theId1, MetaDt theMetaDel) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public MetaDt metaAddOperation(IdDt theId1, MetaDt theMetaAdd) {
		// TODO Auto-generated method stub
		return null;
	}


}