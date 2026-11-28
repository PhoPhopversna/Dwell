#!/usr/bin/env bash
# Sets up Keycloak for Dwell: realm, roles, clients and identity's admin rights. Safe to run again.
#
# Runs automatically as the keycloak-init service of docker/infra/docker-compose.yml. Can also be
# run by hand against any Keycloak (needs bash, curl and jq):
#   KC_URL=https://kc.example.com KC_ADMIN_PASSWORD=... KEYCLOAK_CLIENT_SECRET=... \
#   KEYCLOAK_SERVICE_CLIENT_SECRET=... docker/infra/keycloak-init/setup.sh
#
# Rules (see docs/keycloak-setup.md):
# - Creates what's missing, never deletes, never overwrites what admins may change (descriptions,
#   token lifetimes, roles, which permissions a role has, users).
# - Enforces the few settings Dwell's security depends on, and says so when it fixes one.
# - Client secrets: when KEYCLOAK_CLIENT_SECRET / KEYCLOAK_SERVICE_CLIENT_SECRET are set they are
#   the source of truth (Keycloak is updated to match: that's how you rotate). Never printed.
# - Permissions themselves (product:read, ...) are created by identity at startup, not here.
#
# Environment (every *_SECRET / *_PASSWORD can also be given as <NAME>_FILE, a path to read):
#   KC_URL                          Keycloak base URL                     (http://localhost:8080)
#   KC_ADMIN_USER / KC_ADMIN_PASSWORD  master realm admin                 (admin / admin)
#   KC_WAIT_SECONDS                 how long to wait for Keycloak          (180)
#   KEYCLOAK_REALM                  (Dwell)
#   KEYCLOAK_CLIENT_ID              user logins client                     (dwell-client)
#   KEYCLOAK_CLIENT_SECRET          its secret (required when creating it)
#   KEYCLOAK_SERVICE_CLIENT_ID      identity's admin client                (dwell-admin)
#   KEYCLOAK_SERVICE_CLIENT_SECRET  its secret (required when creating it)
#   PERMISSION_CLIENT_ID            holds the permissions; = DwellPermissions.CLIENT (dwell-api)
#   DWELL_ROLES                     realm roles created when missing       (ADMIN CUSTOMER)

set -euo pipefail

# NAME or NAME_FILE -> value
secret() {
  local name="$1" file_var="${1}_FILE"
  if [[ -n "${!file_var:-}" ]]; then
    [[ -r "${!file_var}" ]] || fail "$file_var: cannot read ${!file_var}"
    tr -d '\r\n' <"${!file_var}"
  else
    printf '%s' "${!name:-${2:-}}"
  fi
}
fail() { echo "ERROR: $*" >&2; exit 1; }
say() { echo "$*"; }
fixed() { echo "FIXED: $*"; }

KC="${KC_URL:-http://localhost:8080}"
KC="${KC%/}"
ADMIN_USER="${KC_ADMIN_USER:-admin}"
ADMIN_PASSWORD="$(secret KC_ADMIN_PASSWORD admin)"
REALM="${KEYCLOAK_REALM:-Dwell}"
LOGIN_CLIENT="${KEYCLOAK_CLIENT_ID:-dwell-client}"
LOGIN_SECRET="$(secret KEYCLOAK_CLIENT_SECRET)"
SERVICE_CLIENT="${KEYCLOAK_SERVICE_CLIENT_ID:-dwell-admin}"
SERVICE_SECRET="$(secret KEYCLOAK_SERVICE_CLIENT_SECRET)"
API_CLIENT="${PERMISSION_CLIENT_ID:-dwell-api}"
SCOPE_ROLE="permission-scope" # DwellPermissions.SCOPE_ROLE
read -r -a ROLES <<<"${DWELL_ROLES:-ADMIN CUSTOMER}"
# realm-management roles identity's admin client needs (docs/keycloak-setup.md step 5.3)
SERVICE_ROLES=(manage-users view-users query-users manage-realm view-realm query-clients view-clients)
A="/admin/realms/$REALM"

for tool in curl jq; do
  command -v "$tool" >/dev/null || fail "needs $tool"
done
[[ "$LOGIN_CLIENT" != "$SERVICE_CLIENT" ]] || fail "KEYCLOAK_SERVICE_CLIENT_ID must not be the login client"

# ---------- HTTP ----------
TOKEN=""
# call METHOD PATH [JSON_BODY] -> response body; stops the script on an HTTP error
call() {
  local method="$1" path="$2" body="${3:-}" out status
  local args=(-sS -X "$method" -H "Authorization: Bearer $TOKEN" -w $'\n%{http_code}')
  [[ -z "$body" ]] || args+=(-H 'Content-Type: application/json' --data-binary "$body")
  out="$(curl "${args[@]}" "$KC$path")" || fail "$method $path: Keycloak unreachable"
  status="${out##*$'\n'}"
  out="${out%$'\n'*}"
  [[ "$status" -lt 400 ]] || fail "$method $path -> HTTP $status: $out"
  printf '%s' "$out"
}
uri() { jq -rn --arg v "$1" '$v|@uri'; }

sign_in() {
  TOKEN="$(curl -sS "$KC/realms/master/protocol/openid-connect/token" \
    --data-urlencode grant_type=password --data-urlencode client_id=admin-cli \
    --data-urlencode "username=$ADMIN_USER" --data-urlencode "password=$ADMIN_PASSWORD" |
    jq -r '.access_token // empty')" || true
  [[ -n "$TOKEN" ]] || fail "could not sign in to $KC as $ADMIN_USER (master realm): check KC_ADMIN_USER / KC_ADMIN_PASSWORD"
}

# ---------- wait for Keycloak ----------
deadline=$(($(date +%s) + ${KC_WAIT_SECONDS:-180}))
until curl -sf -o /dev/null "$KC/realms/master"; do
  [[ $(date +%s) -lt $deadline ]] || fail "Keycloak at $KC not reachable after ${KC_WAIT_SECONDS:-180}s"
  sleep 3
done
sign_in
say "Keycloak at $KC, realm $REALM"

# ---------- helpers ----------
client_uuid() { call GET "$A/clients?clientId=$(uri "$1")" | jq -r '.[0].id // empty'; }

# enforce CLIENT_UUID LABEL JQ_OBJECT : make these client settings hold, log what was fixed
enforce() {
  local id="$1" label="$2" want="$3" current wrong
  current="$(call GET "$A/clients/$id")"
  wrong="$(jq -r --argjson w "$want" '. as $c | [$w | to_entries[] | select($c[.key] != .value) | "\(.key)=\(.value)"] | join(", ")' <<<"$current")"
  if [[ -n "$wrong" ]]; then
    call PUT "$A/clients/$id" "$(jq --argjson w "$want" '. + $w' <<<"$current")" >/dev/null
    fixed "$label: set $wrong"
  fi
}

# set_secret CLIENT_UUID LABEL VALUE : make the client's secret VALUE (if VALUE given)
set_secret() {
  local id="$1" label="$2" value="$3"
  [[ -n "$value" ]] || return 0
  if [[ "$(call GET "$A/clients/$id/client-secret" | jq -r '.value // empty')" != "$value" ]]; then
    call PUT "$A/clients/$id" "$(call GET "$A/clients/$id" | jq --arg s "$value" '. + {secret: $s}')" >/dev/null
    say "set $label's secret from the environment"
  fi
}

# ---------- 1. realm ----------
if [[ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$KC$A")" == 404 ]]; then
  call POST /admin/realms "$(jq -n --arg r "$REALM" '{realm: $r, enabled: true,
    registrationAllowed: false, bruteForceProtected: true, sslRequired: "external"}')" >/dev/null
  say "created realm $REALM"
else
  say "realm $REALM exists"
fi

# ---------- 2. realm roles ----------
for role in "${ROLES[@]}"; do
  if [[ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$KC$A/roles/$(uri "$role")")" == 404 ]]; then
    call POST "$A/roles" "$(jq -n --arg n "$role" '{name: $n, description: "Created by keycloak-init; tick its permissions in Dwell"}')" >/dev/null
    say "created realm role $role"
  fi
done

# ---------- 3. permission client and the holder role ----------
API="$(client_uuid "$API_CLIENT")"
if [[ -z "$API" ]]; then
  call POST "$A/clients" "$(jq -n --arg c "$API_CLIENT" '{clientId: $c, name: "Dwell permissions",
    description: "Holds Dwell'"'"'s permissions as client roles. No logins, no service account.",
    protocol: "openid-connect", publicClient: false, frontchannelLogout: false,
    standardFlowEnabled: false, implicitFlowEnabled: false, directAccessGrantsEnabled: false,
    serviceAccountsEnabled: false}')" >/dev/null
  API="$(client_uuid "$API_CLIENT")"
  say "created client $API_CLIENT"
fi
# Nobody logs in through it
enforce "$API" "$API_CLIENT" '{"standardFlowEnabled":false,"implicitFlowEnabled":false,"directAccessGrantsEnabled":false,"serviceAccountsEnabled":false}'

holder_id() { call GET "$A/clients/$API/roles" | jq -r --arg n "$SCOPE_ROLE" '.[] | select(.name == $n) | .id'; }
HOLDER="$(holder_id)"
if [[ -z "$HOLDER" ]]; then
  call POST "$A/clients/$API/roles" "$(jq -n --arg n "$SCOPE_ROLE" '{name: $n,
    description: "Not a permission: bundles every permission so they reach tokens. Never assign it."}')" >/dev/null
  HOLDER="$(holder_id)"
  say "created holder role $SCOPE_ROLE"
fi

# ---------- 4. login client ----------
LOGIN="$(client_uuid "$LOGIN_CLIENT")"
if [[ -z "$LOGIN" ]]; then
  [[ -n "$LOGIN_SECRET" ]] || fail "client $LOGIN_CLIENT doesn't exist: set KEYCLOAK_CLIENT_SECRET to create it"
  call POST "$A/clients" "$(jq -n --arg c "$LOGIN_CLIENT" --arg s "$LOGIN_SECRET" '{clientId: $c,
    name: "Dwell user logins", protocol: "openid-connect", publicClient: false, secret: $s,
    standardFlowEnabled: false, implicitFlowEnabled: false, directAccessGrantsEnabled: true,
    serviceAccountsEnabled: false, fullScopeAllowed: false}')" >/dev/null
  LOGIN="$(client_uuid "$LOGIN_CLIENT")"
  say "created client $LOGIN_CLIENT"
else
  set_secret "$LOGIN" "$LOGIN_CLIENT" "$LOGIN_SECRET"
fi
# Tokens carry only what's in scope; no service account (identity's security check)
enforce "$LOGIN" "$LOGIN_CLIENT" '{"publicClient":false,"fullScopeAllowed":false,"serviceAccountsEnabled":false,"directAccessGrantsEnabled":true}'

scoped_realm="$(call GET "$A/clients/$LOGIN/scope-mappings/realm" | jq -c '[.[].name]')"
for role in "${ROLES[@]}"; do
  if ! jq -e --arg n "$role" 'index($n)' <<<"$scoped_realm" >/dev/null; then
    call POST "$A/clients/$LOGIN/scope-mappings/realm" "$(call GET "$A/roles/$(uri "$role")" | jq -c '[{id, name}]')" >/dev/null
    say "put realm role $role in $LOGIN_CLIENT's token scope"
  fi
done
if ! call GET "$A/clients/$LOGIN/scope-mappings/clients/$API" | jq -e --arg n "$SCOPE_ROLE" 'any(.[]; .name == $n)' >/dev/null; then
  call POST "$A/clients/$LOGIN/scope-mappings/clients/$API" \
    "$(jq -n --arg id "$HOLDER" --arg n "$SCOPE_ROLE" '[{id: $id, name: $n}]')" >/dev/null
  say "put $SCOPE_ROLE in $LOGIN_CLIENT's token scope"
fi

# ---------- 5. identity's admin client ----------
SVC="$(client_uuid "$SERVICE_CLIENT")"
if [[ -z "$SVC" ]]; then
  [[ -n "$SERVICE_SECRET" ]] || fail "client $SERVICE_CLIENT doesn't exist: set KEYCLOAK_SERVICE_CLIENT_SECRET to create it"
  call POST "$A/clients" "$(jq -n --arg c "$SERVICE_CLIENT" --arg s "$SERVICE_SECRET" '{clientId: $c,
    name: "Dwell identity admin calls", protocol: "openid-connect", publicClient: false, secret: $s,
    standardFlowEnabled: false, implicitFlowEnabled: false, directAccessGrantsEnabled: false,
    serviceAccountsEnabled: true}')" >/dev/null
  SVC="$(client_uuid "$SERVICE_CLIENT")"
  say "created client $SERVICE_CLIENT"
else
  set_secret "$SVC" "$SERVICE_CLIENT" "$SERVICE_SECRET"
fi
# Service account only: nobody can log in through it
enforce "$SVC" "$SERVICE_CLIENT" '{"publicClient":false,"serviceAccountsEnabled":true,"standardFlowEnabled":false,"implicitFlowEnabled":false,"directAccessGrantsEnabled":false}'

RM="$(client_uuid realm-management)"
SA_USER="$(call GET "$A/clients/$SVC/service-account-user" | jq -r .id)"
have_roles="$(call GET "$A/users/$SA_USER/role-mappings/clients/$RM" | jq -c '[.[].name]')"
missing="$(call GET "$A/clients/$RM/roles?briefRepresentation=true&max=500" | jq -c --argjson have "$have_roles" \
  --argjson want "$(printf '%s\n' "${SERVICE_ROLES[@]}" | jq -R . | jq -sc .)" \
  '[.[] | select((.name as $n | $want | index($n)) and (.name as $n | $have | index($n) | not)) | {id, name}]')"
if [[ "$missing" != "[]" ]]; then
  call POST "$A/users/$SA_USER/role-mappings/clients/$RM" "$missing" >/dev/null
  say "gave $SERVICE_CLIENT $(jq -r '[.[].name] | join(", ")' <<<"$missing")"
fi

# Keycloak 26.8.2+: admin API tokens need the kc-admin-api scope, or every admin call gets 401
KC_ADMIN_SCOPE="$(call GET "$A/client-scopes" | jq -r '.[] | select(.name == "kc-admin-api") | .id')"
[[ -n "$KC_ADMIN_SCOPE" ]] || fail "client scope kc-admin-api not found: Dwell needs Keycloak 26.8.2 or later"
if ! call GET "$A/clients/$SVC/default-client-scopes" | jq -e 'any(.[]; .name == "kc-admin-api")' >/dev/null; then
  call PUT "$A/clients/$SVC/default-client-scopes/$KC_ADMIN_SCOPE" >/dev/null
  say "added kc-admin-api to $SERVICE_CLIENT's default scopes"
fi

# Fine-grained admin permissions v2: identity may create the permission client's roles and tick
# them on roles, for that client only (it has no manage-clients)
if ! call GET "$A" | jq -e '.adminPermissionsEnabled == true' >/dev/null; then
  call PUT "$A" "$(jq -n --arg r "$REALM" '{realm: $r, adminPermissionsEnabled: true}')" >/dev/null
  say "enabled admin permissions (v2) for the realm"
fi
PERM_CLIENT="$(client_uuid admin-permissions)"
[[ -n "$PERM_CLIENT" ]] || fail "admin permissions are on but the admin-permissions client is missing"
AUTHZ="$A/clients/$PERM_CLIENT/authz/resource-server"
find_id() { call GET "$AUTHZ/$1?name=$(uri "$2")" | jq -r --arg n "$2" '.[] | select(.name == $n) | .id'; }

# A client policy: Keycloak evaluates a service account's admin permissions with the client as
# the identity, so a user policy naming the service-account user never matches.
POLICY="$SERVICE_CLIENT client"
POLICY_ID="$(find_id policy "$POLICY")"
if [[ -z "$POLICY_ID" ]]; then
  POLICY_ID="$(call POST "$AUTHZ/policy/client" "$(jq -n --arg n "$POLICY" --arg c "$SVC" \
    '{name: $n, logic: "POSITIVE", clients: [$c], description: "identity'"'"'s admin client"}')" | jq -r .id)"
  say "created policy: $POLICY"
fi
for old in $(find_id policy "$SERVICE_CLIENT service account"); do # earlier versions: never matched
  call DELETE "$AUTHZ/policy/$old" >/dev/null
  say "removed the old user policy '$SERVICE_CLIENT service account'"
done

PERMISSION="$SERVICE_CLIENT may manage $API_CLIENT permissions"
PERM_ID="$(find_id permission/scope "$PERMISSION")"
if [[ -z "$PERM_ID" ]]; then
  call POST "$AUTHZ/permission/scope" "$(jq -n --arg n "$PERMISSION" --arg api "$API" --arg p "$POLICY_ID" '{
    name: $n, resourceType: "Clients", scopes: ["map-roles-composite", "manage"],
    resources: [$api], policies: [$p],
    description: "Lets identity create the permissions and tick them on roles"}')" >/dev/null
  say "created admin permission: $PERMISSION"
else
  linked="$(call GET "$AUTHZ/policy/$PERM_ID/associatedPolicies" | jq -c '[.[].id]')"
  scopes="$(call GET "$AUTHZ/policy/$PERM_ID/scopes" | jq -c '[.[].name] | sort')"
  resources="$(call GET "$AUTHZ/policy/$PERM_ID/resources" | jq -c '[.[].name]')"
  if [[ "$linked" != "[\"$POLICY_ID\"]" || "$scopes" != '["manage","map-roles-composite"]' || "$resources" != "[\"$API\"]" ]]; then
    call PUT "$AUTHZ/permission/scope/$PERM_ID" \
      "$(call GET "$AUTHZ/permission/scope/$PERM_ID" | jq --arg p "$POLICY_ID" --arg api "$API" \
        '. + {policies: [$p], resources: [$api], scopes: ["map-roles-composite", "manage"]}')" >/dev/null
    fixed "admin permission '$PERMISSION': $API_CLIENT only, manage + map-roles-composite, policy '$POLICY'"
  fi
fi
for old in $(find_id permission/scope "$SERVICE_CLIENT may tick $API_CLIENT permissions on roles"); do
  call DELETE "$AUTHZ/permission/scope/$old" >/dev/null
  say "removed the old admin permission (map-roles-composite only)"
done

# ---------- 6. lock down the realm ----------
ADMIN_CLI="$(client_uuid admin-cli)"
enforce "$ADMIN_CLI" "admin-cli (realm $REALM)" '{"directAccessGrantsEnabled":false}'

DEFAULT_ROLE="$(call GET "$A" | jq -r '.defaultRole.name')"
leaked="$(call GET "$A/roles/$(uri "$DEFAULT_ROLE")/composites/clients/$RM" | jq -c '[.[] | {id, name}]')"
if [[ "$leaked" != "[]" ]]; then
  call DELETE "$A/roles/$(uri "$DEFAULT_ROLE")/composites" "$leaked" >/dev/null
  fixed "removed $(jq -r '[.[].name] | join(", ")' <<<"$leaked") from the default roles (every user got them)"
fi

say "Keycloak setup done. identity creates the permissions at its next start."
