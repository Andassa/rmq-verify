# RabbitMQ node image for fault injection: the pinned official image plus iptables and iproute2
# (tc netem). The broker itself keeps running as the unprivileged rabbitmq user; only the
# fault-injection commands are executed as root through `docker exec -u 0`, with the single
# NET_ADMIN capability granted by docker-compose.yml.
ARG BASE_IMAGE
FROM ${BASE_IMAGE}

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends iptables iproute2 \
 && rm -rf /var/lib/apt/lists/* \
 && rm -f /etc/rabbitmq/conf.d/*
USER rabbitmq

HEALTHCHECK NONE
