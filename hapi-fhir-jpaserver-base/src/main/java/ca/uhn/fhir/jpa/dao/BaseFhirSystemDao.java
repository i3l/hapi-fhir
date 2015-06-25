package ca.uhn.fhir.jpa.dao;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;

import ca.uhn.fhir.jpa.entity.IResourceTable;
import ca.uhn.fhir.model.api.IResource;

public abstract class BaseFhirSystemDao<T> implements IFhirSystemDao<T> {
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(BaseFhirSystemDao.class);
	private String myEntitiesPackage; //FIXME instance needs to be injected
	private BaseFhirDao baseFhirDao;

	@PersistenceContext()
	protected EntityManager myEntityManager;
	
	public BaseFhirDao getBaseFhirDao() {
		return baseFhirDao;
	}

	public void setBaseFhirDao(BaseFhirDao baseFhirDao) {
		this.baseFhirDao = baseFhirDao;
	}
	
	/**
	 * @return A map with the provided resource types and how many registries are stored in the database for each resource.
	 */
	@Override
	public Map<String, Long> getResourceCounts() {
		Map<String, Long> retVal = new HashMap<String, Long>();

		ClassPathScanningCandidateComponentProvider cpsccp = new ClassPathScanningCandidateComponentProvider(true); 
		TypeFilter filter = new AssignableTypeFilter(IResourceTable.class);
		cpsccp.addIncludeFilter(filter );
		Set<BeanDefinition> beans = cpsccp.findCandidateComponents(myEntitiesPackage);

		CriteriaBuilder builder = myEntityManager.getCriteriaBuilder();
		for (BeanDefinition beanDefinition : beans) {
			String className = beanDefinition.getBeanClassName();
			try {
				Class<?> defClass = Class.forName(className);
				IResourceTable resEntity = (IResourceTable) defClass.newInstance();
				Class<? extends IResource> resType = resEntity.getRelatedResourceType();
				if(resType != null){
					CriteriaQuery<Long> criteria = builder.createQuery(Long.class); 
					criteria.select(builder.count(criteria.from(defClass)));
					Long r = myEntityManager.createQuery(criteria).getSingleResult(); 
					retVal.put(resType.getSimpleName(), r);					
				}
			} catch (ClassNotFoundException e) {
				ourLog.error("Resource count: the class {} wasn't found on the classpath.", className);
			} catch (InstantiationException e) {
				// TODO use mirrors lib
			} catch (IllegalAccessException e) {
			}
		}

		return retVal;
	}

}
