# One-shot job that sets up Keycloak for Dwell (setup.sh), then exits.
FROM alpine:3.20
RUN apk add --no-cache bash curl jq
COPY setup.sh /usr/local/bin/keycloak-init
RUN chmod 755 /usr/local/bin/keycloak-init
USER nobody
ENTRYPOINT ["/usr/local/bin/keycloak-init"]
