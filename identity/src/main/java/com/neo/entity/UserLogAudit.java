package com.neo.entity;

import java.time.LocalDateTime;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("user_log_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLogAudit {
  @Id private Long id;

  @Column("user_id")
  private Long userId;

  private String action;
  private Boolean success;
  private String response;

  @Column("correlation_id")
  private String correlationId;

  @Column("created_at")
  private LocalDateTime createdAt;
}
