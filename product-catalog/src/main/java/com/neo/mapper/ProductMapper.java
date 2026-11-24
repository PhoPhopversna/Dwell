package com.neo.mapper;

import com.neo.dto.ProductImageResponse;
import com.neo.dto.ProductRequest;
import com.neo.dto.ProductResponse;
import com.neo.dto.ProductVariantResponse;
import com.neo.entity.Product;
import com.neo.entity.ProductImage;
import com.neo.entity.ProductVariant;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(
    componentModel = "spring",
    uses = {CategoryResolver.class})
public abstract class ProductMapper {

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "category", source = "categoryId")
  @Mapping(
      target = "status",
      expression = "java(request.getStatus() != null ? request.getStatus() : Product.Status.DRAFT)")
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  // Images are attached in ProductService after upload
  @Mapping(target = "images", ignore = true)
  // Variants need entity-specific logic, so ProductService creates them
  @Mapping(target = "variants", ignore = true)
  public abstract Product toEntity(ProductRequest request);

  @Mapping(target = "categoryId", source = "category.id")
  @Mapping(target = "categoryName", source = "category.name")
  public abstract ProductResponse toResponse(Product product);

  @Mapping(target = "price", source = "priceOverride")
  @Mapping(target = "attributes", source = "variantAttributes")
  @Mapping(target = "effectivePrice", expression = "java(variant.getEffectivePrice())")
  public abstract ProductVariantResponse toResponse(ProductVariant variant);

  public abstract ProductImageResponse toResponse(ProductImage image);

  @AfterMapping
  protected void linkVariantsAndImages(@MappingTarget Product product) {
    if (product.getVariants() != null) {
      product.getVariants().forEach(v -> v.setProduct(product));
    }
    if (product.getImages() != null) {
      product.getImages().forEach(i -> i.setProduct(product));
    }
  }
}
