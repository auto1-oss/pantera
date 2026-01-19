#!/bin/zsh
set -e

curl -XPUT http://localhost:8081/test_prefix/api/helm/helm/cert-manager-v1.15.4.tgz --data-binary @cert-manager-v1.15.4.tgz -u ayd:ayd
curl -XPUT http://localhost:8081/test_prefix/api/helm/helm/cert-manager-v1.17.4.tgz --data-binary @cert-manager-v1.17.4.tgz -u ayd:ayd
curl -XPUT http://localhost:8081/test_prefix/api/helm/helm/ingress-nginx-4.13.3.tgz --data-binary @ingress-nginx-4.13.3.tgz -u ayd:ayd

helm repo add local http://localhost:8081/test_prefix/helm --username ayd --password ayd
helm repo update
OUTPUT=$(helm search repo local | grep -E 'cert|nginx')
if [[ $OUTPUT != *"cert-manager"* ]] || [[ $OUTPUT != *"ingress-nginx"* ]]; then
  echo "Helm charts not found in the repository"
  exit 1
else
    echo "Helm charts  found in the repository"
fi

echo "✅ Helm  test completed successfully!"