#!/bin/zsh

echo  " Uplaoding ayd-helper-lib-1.0.0.zip to Artipie PHP repository..."
curl -v -u ayd:ayd -X PUT \
  --data-binary @ayd-helper-lib-1.0.0.zip \
  "http://localhost:8081/api/php/ayd-helper-lib-1.0.0.zip"

echo " Uplaoding wkda-notification-service-0.1.0.tar.gz to Artipie Composer repository..."
curl -v -u ayd:ayd -X PUT \
  --data-binary @wkda-notification-service-0.1.0.tar.gz \
  "http://localhost:8081/artifactory/api/composer/php/wkda-notification-service-0.1.0.tar.gz"

echo " Uplaoding wkda-notification-service-20220119164424-1e02e050.tar.gz to Artipie Composer repository..."
curl -v -u ayd:ayd -X PUT \
  --data-binary @wkda-notification-service-20220119164424-1e02e050.tar.gz \
  "http://localhost:8081/api/composer/php/wkda-notification-service-20220119164424-1e02e050.tar.gz"