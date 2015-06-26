package ca.uhn.fhir.jpa.dao;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContextType;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.apache.commons.lang3.Validate;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.jpa.entity.BaseResourceEntity;
import ca.uhn.fhir.jpa.entity.ResourceHistoryTable;
import ca.uhn.fhir.jpa.entity.TagDefinition;
import ca.uhn.fhir.jpa.util.StopWatch;
import ca.uhn.fhir.model.api.IQueryParameterAnd;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.api.TagList;
import ca.uhn.fhir.model.dstu2.composite.MetaDt;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.model.primitive.InstantDt;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.method.MethodUtil;
import ca.uhn.fhir.rest.method.QualifiedParamList;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.server.Constants;
import ca.uhn.fhir.rest.server.IBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

import com.google.common.base.Function;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;

public class BaseFhirDao implements IDao{
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(BaseFhirDao.class);
	private FhirContext myContext;
	
	private List<IDaoListener> myListeners = new ArrayList<IDaoListener>();
	
	@PersistenceContext(type = PersistenceContextType.TRANSACTION)
	private EntityManager myEntityManager;

	@Autowired
	private List<IFhirResourceDao<?>> myResourceDaos;

	@Autowired
	private PlatformTransactionManager myPlatformTransactionManager;

	@Autowired(required = true)
	protected DaoConfig myConfig;
	
	private Map<Class<? extends IBaseResource>, IFhirResourceDao<?>> myResourceTypeToDao;
	
	public EntityManager getEntityManager() {
		return myEntityManager;
	}

	public FhirContext getContext() {
		return myContext;
	}

	public void setContext(FhirContext theContext) {
		myContext = theContext;
	}

	protected DaoConfig getConfig() {
		return myConfig;
	}

	protected IFhirResourceDao<? extends IResource> getDao(Class<? extends IBaseResource> theType) {
		if (myResourceTypeToDao == null) {
			myResourceTypeToDao = new HashMap<Class<? extends IBaseResource>, IFhirResourceDao<?>>();
			for (IFhirResourceDao<?> next : myResourceDaos) {
				myResourceTypeToDao.put(next.getResourceType(), next);
			}

			if (this instanceof IFhirResourceDao<?>) {
				IFhirResourceDao<?> thiz = (IFhirResourceDao<?>) this;
				myResourceTypeToDao.put(thiz.getResourceType(), thiz);
			}

		}

		return myResourceTypeToDao.get(theType);
	}

	public Map<Class<? extends IBaseResource>, IFhirResourceDao<?>> getMyResourceTypeToDao() {
		return myResourceTypeToDao;
	}

	protected void notifyWriteCompleted() {
		for (IDaoListener next : myListeners) {
			next.writeCompleted();
		}
	}

	@Override
	public void registerDaoListener(IDaoListener theListener) {
		Validate.notNull(theListener, "theListener");
		myListeners.add(theListener);
	}
	
	protected String toResourceName(Class<? extends IResource> theResourceType) {
		return getContext().getResourceDefinition(theResourceType).getName();
	}

	protected String toResourceName(IResource theResource) {
		return getContext().getResourceDefinition(theResource).getName();
	}
	
	protected boolean isValidPid(IIdType theId) {
		String idPart = theId.getIdPart();
		for (int i = 0; i < idPart.length(); i++) {
			char nextChar = idPart.charAt(i);
			if (nextChar < '0' || nextChar > '9') {
				return false;
			}
		}
		return true;
	}
	
	protected Set<Long> processMatchUrl(String theMatchUrl, Class<? extends IBaseResource> theResourceType) {
		RuntimeResourceDefinition resourceDef = getContext().getResourceDefinition(theResourceType);

		SearchParameterMap paramMap = translateMatchUrl(theMatchUrl, resourceDef);

		IFhirResourceDao<? extends IResource> dao = getDao(theResourceType);
		Set<Long> ids = dao.searchForIdsWithAndOr(paramMap);

		return ids;
	}
	
	InstantDt createHistoryToTimestamp() {
		// final InstantDt end = new InstantDt(DateUtils.addSeconds(DateUtils.truncate(new Date(), Calendar.SECOND),
		// -1));
		return InstantDt.withCurrentTime();
	}
//	
//	private void searchHistoryCurrentVersion(List<HistoryTuple> theTuples, List<BaseResourceEntity> theRetVal) {
//		Collection<HistoryTuple> tuples = Collections2.filter(theTuples, new com.google.common.base.Predicate<HistoryTuple>() {
//			@Override
//			public boolean apply(HistoryTuple theInput) {
//				return theInput.isHistory() == false;
//			}
//		});
//		Collection<Long> ids = Collections2.transform(tuples, new Function<HistoryTuple, Long>() {
//			@Override
//			public Long apply(HistoryTuple theInput) {
//				return theInput.getId();
//			}
//		});
//		if (ids.isEmpty()) {
//			return;
//		}
//
//		CriteriaBuilder builder = getEntityManager().getCriteriaBuilder();
//		CriteriaQuery<ResourceTable> cq = builder.createQuery(BaseResourceEntity.class);
//		Root<ResourceTable> from = cq.from(ResourceTable.class);
//		cq.where(from.get("myId").in(ids));
//
//		cq.orderBy(builder.desc(from.get("myUpdated")));
//		TypedQuery<ResourceTable> q = getEntityManager().createQuery(cq);
//		for (ResourceTable next : q.getResultList()) {
//			theRetVal.add(next);
//		}
//	}
//
//	private void searchHistoryCurrentVersion(String theResourceName, Long theId, Date theSince, Date theEnd, Integer theLimit, List<HistoryTuple> tuples) {
//		CriteriaBuilder builder = getEntityManager().getCriteriaBuilder();
//		CriteriaQuery<Tuple> cq = builder.createTupleQuery();
//		Root<?> from = cq.from(ResourceTable.class);
//		cq.multiselect(from.get("myId").as(Long.class), from.get("myUpdated").as(Date.class));
//
//		List<Predicate> predicates = new ArrayList<Predicate>();
//		if (theSince != null) {
//			Predicate low = builder.greaterThanOrEqualTo(from.<Date> get("myUpdated"), theSince);
//			predicates.add(low);
//		}
//
//		Predicate high = builder.lessThan(from.<Date> get("myUpdated"), theEnd);
//		predicates.add(high);
//
//		if (theResourceName != null) {
//			predicates.add(builder.equal(from.get("myResourceType"), theResourceName));
//		}
//		if (theId != null) {
//			predicates.add(builder.equal(from.get("myId"), theId));
//		}
//
//		cq.where(builder.and(predicates.toArray(new Predicate[0])));
//
//		cq.orderBy(builder.desc(from.get("myUpdated")));
//		TypedQuery<Tuple> q = getEntityManager().createQuery(cq);
//		if (theLimit != null && theLimit < myConfig.getHardSearchLimit()) {
//			q.setMaxResults(theLimit);
//		} else {
//			q.setMaxResults(myConfig.getHardSearchLimit());
//		}
//		for (Tuple next : q.getResultList()) {
//			long id = next.get(0, Long.class);
//			Date updated = next.get(1, Date.class);
//			tuples.add(new HistoryTuple(false, updated, id));
//		}
//	}
//
//	private void searchHistoryHistory(List<HistoryTuple> theTuples, List<BaseResourceEntity> theRetVal) {
//		Collection<HistoryTuple> tuples = Collections2.filter(theTuples, new com.google.common.base.Predicate<HistoryTuple>() {
//			@Override
//			public boolean apply(HistoryTuple theInput) {
//				return theInput.isHistory() == true;
//			}
//		});
//		Collection<Long> ids = Collections2.transform(tuples, new Function<HistoryTuple, Long>() {
//			@Override
//			public Long apply(HistoryTuple theInput) {
//				return (Long) theInput.getId();
//			}
//		});
//		if (ids.isEmpty()) {
//			return;
//		}
//
//		ourLog.info("Retrieving {} history elements from ResourceHistoryTable", ids.size());
//
//		CriteriaBuilder builder = getEntityManager().getCriteriaBuilder();
//		CriteriaQuery<ResourceHistoryTable> cq = builder.createQuery(ResourceHistoryTable.class);
//		Root<ResourceHistoryTable> from = cq.from(ResourceHistoryTable.class);
//		cq.where(from.get("myId").in(ids));
//
//		cq.orderBy(builder.desc(from.get("myUpdated")));
//		TypedQuery<ResourceHistoryTable> q = getEntityManager().createQuery(cq);
//		for (ResourceHistoryTable next : q.getResultList()) {
//			theRetVal.add(next);
//		}
//	}
//
//	private void searchHistoryHistory(String theResourceName, Long theResourceId, Date theSince, Date theEnd, Integer theLimit, List<HistoryTuple> tuples) {
//		CriteriaBuilder builder = getEntityManager().getCriteriaBuilder();
//		CriteriaQuery<Tuple> cq = builder.createTupleQuery();
//		Root<?> from = cq.from(ResourceHistoryTable.class);
//		cq.multiselect(from.get("myId").as(Long.class), from.get("myUpdated").as(Date.class));
//
//		List<Predicate> predicates = new ArrayList<Predicate>();
//		if (theSince != null) {
//			Predicate low = builder.greaterThanOrEqualTo(from.<Date> get("myUpdated"), theSince);
//			predicates.add(low);
//		}
//
//		Predicate high = builder.lessThan(from.<Date> get("myUpdated"), theEnd);
//		predicates.add(high);
//
//		if (theResourceName != null) {
//			predicates.add(builder.equal(from.get("myResourceType"), theResourceName));
//		}
//		if (theResourceId != null) {
//			predicates.add(builder.equal(from.get("myResourceId"), theResourceId));
//		}
//
//		cq.where(builder.and(predicates.toArray(new Predicate[0])));
//
//		cq.orderBy(builder.desc(from.get("myUpdated")));
//		TypedQuery<Tuple> q = getEntityManager().createQuery(cq);
//		if (theLimit != null && theLimit < myConfig.getHardSearchLimit()) {
//			q.setMaxResults(theLimit);
//		} else {
//			q.setMaxResults(myConfig.getHardSearchLimit());
//		}
//		for (Tuple next : q.getResultList()) {
//			Long id = next.get(0, Long.class);
//			Date updated = (Date) next.get(1);
//			tuples.add(new HistoryTuple(true, updated, id));
//		}
//	}
//	protected IBundleProvider history(String theResourceName, Long theId, Date theSince) {
//		final List<HistoryTuple> tuples = new ArrayList<HistoryTuple>();
//
//		final InstantDt end = createHistoryToTimestamp();
//
//		StopWatch timer = new StopWatch();
//
//		int limit = 10000;
//
//		// Get list of IDs
//		searchHistoryCurrentVersion(theResourceName, theId, theSince, end.getValue(), limit, tuples);
//		ourLog.info("Retrieved {} history IDs from current versions in {} ms", tuples.size(), timer.getMillisAndRestart());
//
//		searchHistoryHistory(theResourceName, theId, theSince, end.getValue(), limit, tuples);
//		ourLog.info("Retrieved {} history IDs from previous versions in {} ms", tuples.size(), timer.getMillisAndRestart());
//
//		// Sort merged list
//		Collections.sort(tuples, Collections.reverseOrder());
//		assert tuples.size() < 2 || !tuples.get(tuples.size() - 2).getUpdated().before(tuples.get(tuples.size() - 1).getUpdated()) : tuples.toString();
//
//		return new IBundleProvider() {
//
//			@Override
//			public InstantDt getPublished() {
//				return end;
//			}
//
//			@Override
//			public List<IBaseResource> getResources(final int theFromIndex, final int theToIndex) {
//				final StopWatch timer = new StopWatch();
//				TransactionTemplate template = new TransactionTemplate(myPlatformTransactionManager);
//				return template.execute(new TransactionCallback<List<IBaseResource>>() {
//					@Override
//					public List<IBaseResource> doInTransaction(TransactionStatus theStatus) {
//						List<BaseResourceEntity> resEntities = Lists.newArrayList();
//
//						List<HistoryTuple> tupleSubList = tuples.subList(theFromIndex, theToIndex);
//						searchHistoryCurrentVersion(tupleSubList, resEntities);
//						ourLog.info("Loaded history from current versions in {} ms", timer.getMillisAndRestart());
//
//						searchHistoryHistory(tupleSubList, resEntities);
//						ourLog.info("Loaded history from previous versions in {} ms", timer.getMillisAndRestart());
//
//						Collections.sort(resEntities, new Comparator<BaseResourceEntity>() {
//							@Override
//							public int compare(BaseResourceEntity theO1, BaseResourceEntity theO2) {
//								return theO2.getUpdated().getValue().compareTo(theO1.getUpdated().getValue());
//							}
//						});
//
//						int limit = theToIndex - theFromIndex;
//						if (resEntities.size() > limit) {
//							resEntities = resEntities.subList(0, limit);
//						}
//
//						ArrayList<IBaseResource> retVal = new ArrayList<IBaseResource>();
//						for (BaseResourceEntity next : resEntities) {
//							RuntimeResourceDefinition type;
//							try {
//								type = getContext().getResourceDefinition(next.getResourceType());
//							} catch (DataFormatException e) {
//								if (next.getFhirVersion() != getContext().getVersion().getVersion()) {
//									ourLog.info("Ignoring history resource of type[{}] because it is not compatible with version[{}]", next.getResourceType(), getContext().getVersion().getVersion());
//									continue;
//								}
//								throw e;
//							}
//							IResource resource = (IResource) next.getRelatedResource();
//							retVal.add(resource);
//						}
//						return retVal;
//					}
//				});
//			}
//
//			@Override
//			public int size() {
//				return tuples.size();
//			}
//
//			@Override
//			public Integer preferredPageSize() {
//				return null;
//			}
//		};
//	}

	protected IBundleProvider history(String theResourceName, Long theId, Date theSince) {
		// TODO Auto-generated method stub
		return null;
	}
	
	protected TagList getTags(Class<? extends IResource> theResourceType, IdDt theResourceId) {
		// TODO Auto-generated method stub
				return null;
	}
	
	//FIXME put these methods below in different class
	protected MetaDt toMetaDt(List<TagDefinition> tagDefinitions) {
		MetaDt retVal = new MetaDt();
		for (TagDefinition next : tagDefinitions) {
			switch (next.getTagType()) {
			case PROFILE:
				retVal.addProfile(next.getCode());
				break;
			case SECURITY_LABEL:
				retVal.addSecurity().setSystem(next.getSystem()).setCode(next.getCode()).setDisplay(next.getDisplay());
				break;
			case TAG:
				retVal.addTag().setSystem(next.getSystem()).setCode(next.getCode()).setDisplay(next.getDisplay());
				break;
			}
		}
		return retVal;
	}
	
	protected static String normalizeString(String theString) {
		char[] out = new char[theString.length()];
		theString = Normalizer.normalize(theString, Normalizer.Form.NFD);
		int j = 0;
		for (int i = 0, n = theString.length(); i < n; ++i) {
			char c = theString.charAt(i);
			if (c <= '\u007F') {
				out[j++] = c;
			}
		}
		return new String(out).toUpperCase();
	}

	static SearchParameterMap translateMatchUrl(String theMatchUrl, RuntimeResourceDefinition resourceDef) {
		SearchParameterMap paramMap = new SearchParameterMap();
		List<NameValuePair> parameters;
		try {
			String matchUrl = theMatchUrl;
			if (matchUrl.indexOf('?') == -1) {
				throw new InvalidRequestException("Failed to parse match URL[" + theMatchUrl + "] - Error was: URL does not contain any parameters ('?' not detected)");
			}
			matchUrl = matchUrl.replace("|", "%7C");
			matchUrl = matchUrl.replace("=>=", "=%3E%3D");
			matchUrl = matchUrl.replace("=<=", "=%3C%3D");
			matchUrl = matchUrl.replace("=>", "=%3E");
			matchUrl = matchUrl.replace("=<", "=%3C");
			parameters = URLEncodedUtils.parse(new URI(matchUrl), "UTF-8");
		} catch (URISyntaxException e) {
			throw new InvalidRequestException("Failed to parse match URL[" + theMatchUrl + "] - Error was: " + e.toString());
		}

		ArrayListMultimap<String, QualifiedParamList> nameToParamLists = ArrayListMultimap.create();
		for (NameValuePair next : parameters) {
			String paramName = next.getName();
			String qualifier = null;
			for (int i = 0; i < paramMap.size(); i++) {
				switch (paramName.charAt(i)) {
				case '.':
				case ':':
					qualifier = paramName.substring(i);
					paramName = paramName.substring(0, i);
					i = Integer.MAX_VALUE;
					break;
				}
			}

			QualifiedParamList paramList = QualifiedParamList.splitQueryStringByCommasIgnoreEscape(qualifier, next.getValue());
			nameToParamLists.put(paramName, paramList);
		}

		for (String nextParamName : nameToParamLists.keySet()) {
			List<QualifiedParamList> paramList = nameToParamLists.get(nextParamName);
			if (Constants.PARAM_LASTUPDATED.equals(nextParamName)) {
				if (paramList != null && paramList.size() > 0) {
					if (paramList.size() > 2) {
						throw new InvalidRequestException("Failed to parse match URL[" + theMatchUrl + "] - Can not have more than 2 " + Constants.PARAM_LASTUPDATED + " parameter repetitions");
					} else {
						DateRangeParam p1 = new DateRangeParam();
						p1.setValuesAsQueryTokens(paramList);
						paramMap.setLastUpdated(p1);
					}
				}
				continue;
			}

			RuntimeSearchParam paramDef = resourceDef.getSearchParam(nextParamName);
			if (paramDef == null) {
				throw new InvalidRequestException("Failed to parse match URL[" + theMatchUrl + "] - Resource type " + resourceDef.getName() + " does not have a parameter with name: " + nextParamName);
			}

			IQueryParameterAnd<?> param = MethodUtil.parseQueryParams(paramDef, nextParamName, paramList);
			paramMap.add(nextParamName, param);
		}
		return paramMap;
	}
	
	public BaseResourceEntity updateEntity(final IResource theResource, BaseResourceEntity entity, boolean theUpdateHistory, Date theDeletedTimestampOrNull) {
		return updateEntity(theResource, entity, theUpdateHistory, theDeletedTimestampOrNull, true, true);
	}

	public BaseResourceEntity updateEntity(final IResource theResource, BaseResourceEntity entity, boolean theUpdateHistory, Date theDeletedTimestampOrNull, boolean thePerformIndexing,
			boolean theUpdateVersion) {
		//TODO History
//		if (theResource != null) {
//			String resourceType = getContext().getResourceDefinition(theResource).getName();
//			if (isNotBlank(entity.getResourceType()) && !entity.getResourceType().equals(resourceType)) {
//				throw new UnprocessableEntityException("Existing resource ID[" + entity.getIdDt().toUnqualifiedVersionless() + "] is of type[" + entity.getResourceType() + "] - Cannot update with ["
//						+ resourceType + "]");
//			}
//		}
//
//		if (theUpdateHistory) {
//			final ResourceHistoryTable historyEntry = entity.toHistory();
//			myEntityManager.persist(historyEntry);
//		}
//
//		if (theUpdateVersion) {
//			entity.setVersion(entity.getVersion() + 1);
//		}


		if (theDeletedTimestampOrNull != null) {
//			entity.setDeleted(theDeletedTimestampOrNull);
//			entity.setUpdated(theDeletedTimestampOrNull);

		} else {

//			entity.setDeleted(null);

			if (thePerformIndexing) {

//				links = extractResourceLinks(entity, theResource);
//				populateResourceIntoEntity(theResource, entity);
//				entity.setUpdated(new Date());
//				entity.setLanguage(theResource.getLanguage().getValue());
//				entity.setResourceLinks(links);
//				entity.setHasLinks(links.isEmpty() == false);

			} else {

//				populateResourceIntoEntity(theResource, entity);
//				entity.setUpdated(new Date());
//				entity.setLanguage(theResource.getLanguage().getValue());

			}

		}

		if (entity.getId() == null) {
			myEntityManager.persist(entity);
		} else {
			entity = myEntityManager.merge(entity);
		}

		myEntityManager.flush();

		if (theResource != null) {
			theResource.setId(new IdDt(entity.getId()));
		}

		return entity;
	}

}
