package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public class RouteResponse {
  public Long id;
  public String uri;
  public String path;
  public String method;
  public String description;

  @JsonProperty("rate_limit")
  public Integer rateLimit;

  @JsonProperty("rate_limit_duration")
  public Integer rateLimitDuration;

  public String status;

  @JsonProperty("created_at")
  public LocalDateTime createdAt;

  @JsonProperty("created_by")
  public String createdBy;

  @JsonProperty("update_at")
  public LocalDateTime updatedAt;

  @JsonProperty("updated_by")
  public String updatedBy;
}
