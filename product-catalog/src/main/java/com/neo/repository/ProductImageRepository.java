package com.neo.repository;

import com.neo.entity.ProductImage;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {

  List<ProductImage> findByProductIdOrderByPositionAsc(UUID productId);

  @Modifying
  @Transactional
  @Query(
      "update ProductImage i set i.thumbnailUrl = :thumbnailUrl, i.status = :status "
          + "where i.id = :id")
  int markReady(
      @Param("id") UUID id,
      @Param("thumbnailUrl") String thumbnailUrl,
      @Param("status") ProductImage.ImageStatus status);

  @Modifying
  @Transactional
  @Query("update ProductImage i set i.status = :status where i.id = :id")
  int updateStatus(@Param("id") UUID id, @Param("status") ProductImage.ImageStatus status);
}
