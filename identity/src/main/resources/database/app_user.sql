CREATE SEQUENCE IF NOT EXISTS user_register_seq;

CREATE TABLE IF NOT EXISTS app_user (
    id          BIGINT PRIMARY KEY DEFAULT nextval('user_register_seq'),
    username    VARCHAR(50)  NOT NULL,
    email       VARCHAR(100) NOT NULL UNIQUE,
    keycloak_id VARCHAR(100) NOT NULL UNIQUE,
    first_name  VARCHAR(50),
    last_name   VARCHAR(50),
    active      BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(50) NOT NULL
);