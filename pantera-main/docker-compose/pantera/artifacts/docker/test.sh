#!/bin/zsh

set -e
PANTERA_PASSWORD=ayd
PANTERA_USER=ayd

echo " Docker login to Pantera Docker registry "
docker logout localhost:8081 >/dev/null 2>&1 || true
echo "$PANTERA_PASSWORD" | docker login localhost:8081 -u "$PANTERA_USER" --password-stdin

echo " Docker pull image from Pantera Docker registry "
docker pull localhost:8081/test_prefix/docker_group/voxpupuli/renovate
docker pull localhost:8081/test_prefix/api/docker/docker_group/beats/filebeat:9.1.2
docker pull localhost:8081/test_prefix/docker_group/dart:3-alpine3.22

echo " Docker build and push image to Pantera Docker registry "
docker build . -t localhost:8081/test_prefix/docker_local/auto1/hello:1.0.0
docker push localhost:8081/test_prefix/docker_local/auto1/hello:1.0.0

echo " Docker pull freshly pushed image from Pantera Docker registry 403"
OUTPUT=$(docker pull localhost:8081/test_prefix/docker_group/rachelos/we-mp-rss 2>&1 || true)
if [[ $OUTPUT == *"403 Forbidden"* ]]; then
  echo "Received expected 403 Forbidden error when pulling restricted image"
else
     echo "  ✗ Expected 403 cooldown block but got:"
     echo "    $OUTPUT"
fi

echo " ✅ All Docker registry tests passed "

