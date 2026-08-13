package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neo.entity.AttributeDefinition;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class AttributeDefinitionRequest {

    @JsonProperty("category_id")
    private UUID categoryID;

    @Size(max = 60)
    @NotNull
    private String key;
    @Size(max = 120)
    @NotNull
    private String label;

    @JsonProperty("data_type")
    @NotNull
    private AttributeDefinition.DataType dataType;

    private List<String> options;
    private boolean required;
    private boolean filterable;


}
