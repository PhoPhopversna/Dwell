package com.neo.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "attribute_definitions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"category_id", "attr_key"}))
@Getter
@Setter
@NoArgsConstructor
public class AttributeDefinition {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JsonBackReference
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /** Machine-readable name used as the key inside Product.attributes, e.g. "screen_size". */
    @Column(name = "attr_key", nullable = false, length = 60)
    private String key;

    /** Human-readable label shown on screen, e.g. "Screen size (inches)". */
    @Column(nullable = false, length = 120)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 20)
    private DataType dataType;

    /** Only used when dataType is SELECT or MULTISELECT — the list of valid choices. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> options;

    @Column(name = "is_required", nullable = false)
    private boolean required = false;

    @Column(name = "is_filterable", nullable = false)
    private boolean filterable = false;

    public enum DataType {
        TEXT, NUMBER, BOOLEAN, SELECT, MULTISELECT, DATE
    }

    public AttributeDefinition(Category category, String key, String label, DataType dataType) {
        this.category = category;
        this.key = key;
        this.label = label;
        this.dataType = dataType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AttributeDefinition other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

}
