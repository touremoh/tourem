package com.tourem.service;

import com.google.common.base.Strings;
import com.tourem.dao.entities.TouremEntity;
import com.tourem.dao.repositories.TouremRepository;
import com.tourem.dao.specifications.TouremQueryBuilder;
import com.tourem.dto.TouremDto;
import com.tourem.exceptions.ResourceCreationFailedException;
import com.tourem.exceptions.ResourceNotFoundException;
import com.tourem.mappers.TouremObjectMapper;
import com.tourem.validation.TouremValidation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static java.util.Optional.ofNullable;

@Slf4j
public abstract class AbstractTouremService<E extends TouremEntity, D extends TouremDto> implements TouremService<D> {

	protected final TouremRepository<E> repository;
	protected final TouremObjectMapper<E, D> mapper;
	protected final TouremQueryBuilder<E> queryBuilder;
	protected final TouremValidation<E> validator;

	protected AbstractTouremService(TouremRepository<E> repository, TouremObjectMapper<E, D> mapper, TouremQueryBuilder<E> queryBuilder, TouremValidation<E> validator) {
		this.repository = repository;
		this.mapper = mapper;
		this.queryBuilder = queryBuilder;
		this.validator = validator;
	}

	/**
	 * Find one element by its ID
	 * @param id ID of the element to be found
	 * @return returns the found element
	 */
	@Override
	@Transactional(readOnly = true)
	public D find(String id) {
		return this.repository
				   .findById(id)
				   .map(mapper::mapToDto)
				   .orElseThrow(() -> new ResourceNotFoundException(String.format("Resource with ID [%s] not found", id)));
	}

	/**
	 * Find one element using different criteria other than resource ID
	 * @param criteria request criteria to be found
	 * @return returns the found element
	 */
	@Override
	@Transactional(readOnly = true)
	public D find(Map<String, String> criteria) {
		return this.repository
				   .findOne(this.queryBuilder.buildQuerySpecification(criteria))
				   .map(mapper::mapToDto)
				   .orElseThrow(() -> new ResourceNotFoundException(String.format("Resource with criteria [%s] not found", criteria)));
	}

	/**
	 * Create a new resource
	 * @param data resource information
	 * @return returns the new resource created
	 */
	@Override
	@Transactional
	public D create(D data) {
		// map to entity
		E e = mapper.mapToEntity(data);

		// validate
		applyPrePersistValidation(e);

		// pre persist
		processBeforeCreate(e);

		// save
		E res = this.repository.save(e);

		// post persist
		processAfterCreate(res);

		// map and return results
		return mapper.mapToDto(res);
	}

	protected void applyPrePersistValidation(E entity) {
		log.info("Pre persist validation of [{}]", entity);

		var vResults = this.validator.validate(entity);

		if (!vResults.isEmpty()) {
			log.debug("Entity [{}] validation failed with message: [{}]", entity, vResults);
			throw new IllegalArgumentException(String.format("Entity [%s] validation failed with message: [%s]", entity, vResults));
		}

		if (entity.hasId()) {
			log.debug("Field id not allowed for create operation for object: [{}]", entity);
			throw new IllegalArgumentException(String.format("Field id not allowed for create operation for object: [%s]", entity));
		}

		if (Objects.nonNull(entity.getCreatedAt())) {
			log.debug("Field createdAt not allowed for create operation for object: [{}]", entity);
			throw new IllegalArgumentException(String.format("Field createdAt not allowed for create operation for object: [%s]", entity));
		}

		if (Objects.nonNull(entity.getUpdatedAt())) {
			log.debug("Field updatedAt not allowed for create operation for entity [{}]", entity);
			throw new IllegalArgumentException(String.format("Field updatedAt not allowed for create operation for entity [%s]", entity));
		}

		if (Objects.nonNull(entity.getDeletedAt())) {
			log.debug("Field deletedAt not allowed for create operation for entity [{}]", entity);
			throw new IllegalArgumentException(String.format("Field deletedAt not allowed for create operation for entity [%s]", entity));
		}
	}

	protected void processBeforeCreate(E entity) {
		log.info("Processing entity before create: [{}]", entity);
	}

	protected void processAfterCreate(E entity) {
		log.info("Processing entity after create: [{}]", entity);

		// check if entity has been persisted
		try {
			ofNullable(this.find(entity.getId()))
				.ifPresent(e -> log.info(String.format("Resource successfully persisted in DB [%s]", e)));

		} catch (Exception e) {
			log.debug(String.format("Unable to persisted resource in DB in DB [%s]", entity));
			throw new ResourceCreationFailedException(String.format("Resource created but not persisted in DB [%s]", entity));
		}
	}

	/**
	 * Update one part of an element
	 *
	 * @param data update data
	 * @return returns the newly updated resource
	 */
	@Override
	@Transactional
	public D patch(D data) {
		// map to entity
		E e = this.mapper.mapToEntity(data);

		// initial validation
		applyInitialCheckBeforePatch(e);

		// process before update
		processBeforePatch(e);

		// save
		E res = this.repository.save(e);

		// process after update
		processAfterPatch(res);

		// map to dto and returns
		return this.mapper.mapToDto(res);
	}

	public void applyInitialCheckBeforePatch(E entity) {
		log.info("Validation of [{}] before PATCH", entity);

		if (!entity.hasId()) {
			log.debug("The ID of the object to update is missing - [{}]", entity);
			throw new IllegalArgumentException(String.format("The ID is mandatory for update operation: [%s]", entity));
		}

		if (!this.repository.existsById(entity.getId())) {
			log.debug("Trying to update a row with an invalid ID : [{}]", entity);
			throw new IllegalArgumentException(String.format("The ID of the row to update is not valid [%s]", entity));
		}

		if (entity.hasDeletedAt()) {
			log.debug("Using deleteAt field on update operation [{}]", entity);
			throw new IllegalArgumentException(String.format("Field deletedAt not allowed for update operation for entity [%s]", entity));
		}
	}

	protected void processBeforePatch(E entity) {
		log.info("Processing entity before patch: [{}]", entity);
		this.repository
			.findById(entity.getId())
			.ifPresent(source -> mergeSourceToTarget(source, entity));
	}

	protected void processAfterPatch(E entity) {
		log.info("Processing entity after patch: [{}]", entity);
	}

	/**
	 * Replace the content of the element
	 *
	 * @param data update data
	 * @return returns the newly updated resource
	 */
	@Override
	@Transactional
	public D put(D data) {
		// map to entity
		E e = this.mapper.mapToEntity(data);

		// initial check
		applyInitialCheckBeforePut(e);

		// process before put
		processBeforePut(e);

		// save
		E res = this.repository.save(e);

		// process after put
		processAfterPut(res);

		// map to dto and return
		return this.mapper.mapToDto(res);
	}

	public void applyInitialCheckBeforePut(E entity) {
		log.info("Validation of [{}] before PUT", entity);

		this.applyInitialCheckBeforePatch(entity);

		var vResults = this.validator.validate(entity);

		if (!vResults.isEmpty()) {
			log.debug("Entity [{}] validation failed with message: [{}]", entity, vResults);
			throw new IllegalArgumentException(String.format("Entity [%s] validation failed with message: [%s]", entity, vResults));
		}
	}

	protected void processBeforePut(E entity) {
		log.info("Pre processing entity before put: [{}]", entity);
		this.repository.findById(entity.getId()).ifPresent(e -> entity.setCreatedAt(e.getCreatedAt()));
	}

	protected void processAfterPut(E entity) {
		log.info("Pre processing entity after put: [{}]", entity);
	}

	/**
	 * Delete a resource by its ID
	 * @param id ID of the resource to be deleted
	 */
	@Override
	public void delete(String id) {
		// check if ID exists before delete
		if (!this.repository.existsById(id)) {
			log.debug("Trying to remove a resource with an invalid ID: [{}]", id);
			throw new IllegalArgumentException(String.format("The resource you are trying to remove does not exists [%s]", id));
		}

		// delete resource
		this.repository.deleteById(id);

		// check if the delete operation was successful
		if (this.repository.existsById(id)) {
			log.debug("The delete operation was not successful for resource with ID: [{}]", id);
			throw new IllegalArgumentException(String.format("An error occurred during delete operation - Resource [%s] not deleted", id));
		}
	}

	/**
	 * Find many elements by criteria
	 * @param criteria Criteria of the resources to be found
	 * @return returns one or many pages
	 */
	@Override
	@Transactional(readOnly = true)
	public Page<D> findAll(Map<String, String> criteria) {
		// build page request
		var req = processBeforeFindAll(criteria);

		// build query criteria
		var querySpec = this.queryBuilder.buildQuerySpecification(criteria);


		// run query and process results
		return this.repository.findAll(querySpec, req).map(mapper::mapToDto);
	}

	public PageRequest processBeforeFindAll(Map<String, String> criteria) {
		var size = criteria.get("size");
		var page = criteria.get("page");
		var sortBy = criteria.get("sortBy");
		var sortDirection = criteria.get("sortDirection");

		if (!Strings.isNullOrEmpty(size)) {
			if (!Strings.isNullOrEmpty(page)) {
				if (!Strings.isNullOrEmpty(sortBy) && !Strings.isNullOrEmpty(sortDirection)) {
					var sort = sortDirection.equals("ASC")
						? Sort.by(sortBy).ascending()
						: Sort.by(sortBy).descending();
					return PageRequest.of(Integer.parseInt(page), Integer.parseInt(size), sort);
				}
				return PageRequest.of(Integer.parseInt(page), Integer.parseInt(size));
			}
			return PageRequest.ofSize(Integer.parseInt(size));
		}
		return PageRequest.ofSize(50);
	}

	public void mergeSourceToTarget(E source, E target) {
		final var wTarget = new BeanWrapperImpl(target);

		List<String> ignoredProperties = new ArrayList<>();

		for (var pd : wTarget.getPropertyDescriptors()) {
			if (Objects.nonNull(wTarget.getPropertyValue(pd.getName()))) {
				ignoredProperties.add(pd.getName());
			}
		}
		BeanUtils.copyProperties(source, target, ignoredProperties.toArray(new String[0]));
	}
}
