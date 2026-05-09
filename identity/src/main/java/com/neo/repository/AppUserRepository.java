package com.neo.repository;

import com.neo.entity.AppUser;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

public interface AppUserRepository extends R2dbcRepository<AppUser, Long> {}
