package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

@Getter
public class RouteRequest {
  public Long id;
  public String uri;
  public String path;
  public String method;
  public String description;
  public String status;

  @JsonProperty("group_code")
  public String groupCode;

  @JsonProperty("path_pattern")
  private String pathPattern;

  @JsonProperty("normalized_path")
  private String normalizedPath;

  @JsonProperty("rate_limit")
  public Integer rateLimit;

  @JsonProperty("rate_limit_duration")
  public Integer rateLimitDuration;

  /**
   * Permissions allowed to call this route (any one is enough); ["PUBLIC"] means no token needed.
   */
  public List<String> permissions;

  @JsonProperty("created_at")
  public LocalDateTime createdAt;

  @JsonProperty("created_by")
  public String createdBy;

  @JsonProperty("update_at")
  public LocalDateTime updatedAt;

  @JsonProperty("updated_by")
  public String updatedBy;
}
