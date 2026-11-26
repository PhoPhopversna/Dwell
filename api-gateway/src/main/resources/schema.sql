CREATE SEQUENCE IF NOT EXISTS api_routes_id_seq;
CREATE TABLE IF NOT EXISTS routes (
    id BIGINT PRIMARY KEY DEFAULT nextval('api_routes_id_seq'),
    uri VARCHAR(255) NOT NULL,
    path VARCHAR(255) NOT NULL,
    method VARCHAR(10) NOT NULL,
    description VARCHAR(255),
    group_code VARCHAR(50),
    rate_limit INT DEFAULT 0,
    rate_limit_duration INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    path_pattern VARCHAR(255),
    normalized_path VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(50)
);

-- Permissions allowed per route (comma-separated, any one is enough) or PUBLIC. NULL/empty = deny.
-- Replaces the old "roles" column. Plain statements, each safe to run on every startup (the
-- populator splits on semicolons, so no DO block): old values are converted the way the default
-- routes used them; any other value is copied as it was, and since a role name is never a
-- permission, such a route denies everyone until it is updated (fails closed).
ALTER TABLE routes ADD COLUMN IF NOT EXISTS permissions VARCHAR(500);
ALTER TABLE routes ADD COLUMN IF NOT EXISTS roles VARCHAR(500);
UPDATE routes SET permissions = CASE
    WHEN roles = 'PUBLIC' THEN 'PUBLIC'
    WHEN path LIKE '/api/categories%' AND method = 'GET' THEN 'category:read'
    WHEN path LIKE '/api/categories%' THEN 'category:write'
    WHEN path LIKE '/api/v1/products%' AND method = 'GET' THEN 'product:read'
    WHEN path LIKE '/api/v1/products%' THEN 'product:write'
    WHEN path LIKE '/identity/api/roles%' THEN 'role:read,role:write,user-role:assign'
    WHEN path LIKE '/identity/api/users%' THEN 'user:manage'
    ELSE roles
  END
WHERE permissions IS NULL AND roles IS NOT NULL;
ALTER TABLE routes DROP COLUMN IF EXISTS roles;
