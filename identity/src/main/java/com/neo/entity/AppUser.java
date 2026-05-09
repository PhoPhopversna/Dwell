package com.neo.entity;

import java.time.LocalDateTime;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("app_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser {
  @Id private Long id;

  private String username;
  private String email;
  private Boolean active;

  @Column("keycloak_id")
  private String keycloakId;

  @Column("first_name")
  private String firstName;

  @Column("last_name")
  private String lastName;

  @Column("created_at")
  private LocalDateTime createdAt;

  @Column("created_by")
  private String createdBy;
}
