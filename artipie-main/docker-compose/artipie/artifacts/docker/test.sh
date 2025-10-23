#!/bin/zsh

set -e

echo " Docker login to Artipie Docker registry "
docker login -u ayd -p ayd localhost:8081

echo " Docker pull image from Artipie Docker registry "
docker pull localhost:8081/artifactory/docker_group/voxpupuli/renovate
docker pull localhost:8081/artifactory/api/docker/docker_group/beats/filebeat:9.1.2

echo " Docker build and push image to Artipie Docker registry "
docker build . -t localhost:8081/artifactory/docker_local/auto1/hello:1.0.0
docker push localhost:8081/artifactory/docker_local/auto1/hello:1.0.0

echo " Docker pull freshly pushed image from Artipie Docker registry 403"
OUTPUT=$(docker pull localhost:8081/artifactory/docker_group/rachelos/we-mp-rss 2>&1 || true)
if [[ $OUTPUT == *"403 Forbidden"* ]]; then
  echo "Received expected 403 Forbidden error when pulling restricted image"
else
   exit 1
fi

echo " ✅ All Docker registry tests passed "

