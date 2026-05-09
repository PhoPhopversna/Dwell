CREATE SEQUENCE IF NOT EXISTS user_log_audit_seq;

CREATE TABLE IF NOT EXISTS user_log_audit (
    id         BIGINT PRIMARY KEY DEFAULT nextval('user_log_audit_seq'),
    user_id    BIGINT REFERENCES app_user(id),
    action     VARCHAR(50),
    success    BOOLEAN,
    response   VARCHAR(255),
    correlation_id   VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);