package ca.uhn.fhir.jpa.entity;

import javax.persistence.MappedSuperclass;

@MappedSuperclass
public abstract class BaseResourceEntity implements IResourceTable{

	public abstract Long getId();
}
