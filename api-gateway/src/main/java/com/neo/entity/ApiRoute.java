package com.neo.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "routes")
public class ApiRoute {

  @Id private Long id;
  private String uri;
  private String path;
  private String method;
  private String description;
  private String groupCode;
  private Integer rateLimit;
  private Integer rateLimitDuration;
  private String status;
  private String pathPattern; // e.g. /users/\d+
  private String normalizedPath; // e.g. /users/{userId}
  private LocalDateTime createdAt;
  private String createdBy;
  private LocalDateTime updatedAt;
  private String updatedBy;
}
