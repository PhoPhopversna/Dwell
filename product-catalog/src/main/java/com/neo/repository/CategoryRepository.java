package com.neo.repository;

import com.neo.entity.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
  public List<Category> findAll();

  public Optional<Category> findById(UUID id);
}
