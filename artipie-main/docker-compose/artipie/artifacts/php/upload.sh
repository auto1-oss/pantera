#!/bin/zsh

echo  " Uplaoding ayd-helper-lib-1.0.0.zip to Artipie PHP repository..."
curl -v -u ayd:ayd -X PUT \
  --data-binary @ayd-helper-lib-1.0.0.zip \
  "http://localhost:8081/api/php/ayd-helper-lib-1.0.0.zip"

echo  " Uplaoding ayd-helper-lib-1.1.0.zip to Artipie PHP repository..."
curl -v -u ayd:ayd -X PUT \
  --data-binary @ayd-helper-lib-1.1.0.zip \
  "http://localhost:8081/api/php/ayd-helper-lib-1.1.0.zip"

echo  " Uplaoding ayd-helper-lib-2.0.0.zip to Artipie PHP repository..."
curl -v -u ayd:ayd -X PUT \
  --data-binary @ayd-helper-lib-2.0.0.zip \
  "http://localhost:8081/api/php/ayd-helper-lib-2.0.0.zip"