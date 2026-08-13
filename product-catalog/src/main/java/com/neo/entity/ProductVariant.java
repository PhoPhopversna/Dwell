package com.neo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "product_variants")
@Getter
@Setter
@NoArgsConstructor
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    /** What distinguishes this variant, e.g. {"size":"M","color":"red"}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variant_attributes", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> variantAttributes = new HashMap<>();

    /** Null means "use the parent product's basePrice". */
    @Column(name = "price_override", precision = 12, scale = 2)
    private BigDecimal priceOverride;

    @Column(length = 64)
    private String barcode;

    public ProductVariant(Product product, String sku, Map<String, Object> variantAttributes) {
        this.product = product;
        this.sku = sku;
        this.variantAttributes = variantAttributes;
    }

    /** Resolves the effective selling price, falling back to the parent product's base price. */
    public BigDecimal getEffectivePrice() {
        return priceOverride != null ? priceOverride : product.getBasePrice();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductVariant other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
