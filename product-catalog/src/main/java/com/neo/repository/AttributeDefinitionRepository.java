package com.neo.repository;

import com.neo.entity.AttributeDefinition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttributeDefinitionRepository extends JpaRepository<AttributeDefinition, UUID> {

  List<AttributeDefinition> findByCategoryId(UUID categoryId);

  Optional<AttributeDefinition> findByIdAndCategoryId(UUID id, UUID categoryId);

  boolean existsByCategoryIdAndKey(UUID categoryId, String key);
}
