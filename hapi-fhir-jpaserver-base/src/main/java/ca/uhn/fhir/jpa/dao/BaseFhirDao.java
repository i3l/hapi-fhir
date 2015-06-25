package ca.uhn.fhir.jpa.dao;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContextType;

import org.apache.commons.lang3.Validate;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.jpa.entity.BaseResourceEntity;
import ca.uhn.fhir.model.api.IQueryParameterAnd;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.method.MethodUtil;
import ca.uhn.fhir.rest.method.QualifiedParamList;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.server.Constants;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

import com.google.common.collect.ArrayListMultimap;

public class BaseFhirDao implements IDao{

	private FhirContext myContext;
	private static final Map<FhirVersionEnum, FhirContext> ourRetrievalContexts = new HashMap<FhirVersionEnum, FhirContext>();
	private List<IDaoListener> myListeners = new ArrayList<IDaoListener>();
	
	@PersistenceContext(type = PersistenceContextType.TRANSACTION)
	private EntityManager myEntityManager;

	@Autowired
	private List<IFhirResourceDao<?>> myResourceDaos;

	@Autowired(required = true)
	protected DaoConfig myConfig;
	
	private Map<Class<? extends IBaseResource>, IFhirResourceDao<?>> myResourceTypeToDao;
	
	public EntityManager getEntityManager() {
		return myEntityManager;
	}

	public FhirContext getContext() {
		return myContext;
	}

	public FhirContext getContext(FhirVersionEnum theVersion) {
		FhirVersionEnum ver = theVersion != null ? theVersion : FhirVersionEnum.DSTU1;
		synchronized (ourRetrievalContexts) {
			FhirContext retVal = ourRetrievalContexts.get(ver);
			if (retVal == null) {
				retVal = new FhirContext(ver);
				ourRetrievalContexts.put(ver, retVal);
			}
			return retVal;
		}
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
	
	//FIXME put this in different class
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
