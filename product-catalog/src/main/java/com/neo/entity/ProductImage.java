package com.neo.entity;

import jakarta.persistence.*;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "product_images")
@Getter
@Setter
@NoArgsConstructor
public class ProductImage {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "product_id", nullable = false)
  private Product product;

  @Column(nullable = false, length = 500)
  private String url;

  @Column(length = 500)
  private String thumbnailUrl;

  /** Display order within the product's gallery — 0 shows first. */
  @Column(nullable = false)
  private int position;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ImageStatus status = ImageStatus.PROCESSING;

  public enum ImageStatus {
    PROCESSING,
    READY,
    FAILED
  }

  public ProductImage(String url, int position) {
    this.url = url;
    this.position = position;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ProductImage other)) return false;
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
