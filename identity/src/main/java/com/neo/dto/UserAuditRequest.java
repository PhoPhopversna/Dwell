package com.neo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserAuditRequest {
  private Long userId;
  private String action;
  private Boolean success;
  private String response;
  private String correlationId;
}
