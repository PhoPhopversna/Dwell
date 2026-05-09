package com.neo.repository;

import com.neo.entity.UserLogAudit;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

public interface UserLogAuditRepository extends R2dbcRepository<UserLogAudit, Long> {}
