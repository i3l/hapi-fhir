package ca.uhn.fhir.jpa.entity;

import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.primitive.InstantDt;

/**
 * This interface is used to mapping properties of an Entity to a given Resource.
 * @author Ismael Sarmento
 */
public interface IResourceEntity {
	
	public Long getId();
	
	public FhirVersionEnum getFhirVersion();
	/**
	 * @return The Resource Class related to the Entity. 
	 */
	public String getResourceType();
	
	public InstantDt getUpdated();

	/**
	 * Creates an object of the Resource type related to this entity and sets each property accordingly.
	 * @return the related Resource Instance.
	 */
	public IResource getRelatedResource();
	
	/**
	 * @param The Resource with the properties used to construct the Entity.
	 * @return The Entity constructed using the related Resource(method param) properties
	 */
	public IResourceEntity constructEntityFromResource(IResource resource);
	
}
