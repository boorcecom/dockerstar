#!/bin/sh
cd "$(dirname "$0")" || exit 1
set -a; . ./.env; set +a

openssl pkcs12 -export -out /root/certificate.pfx -inkey /etc/letsencrypt/live/boorce.com/privkey.pem -in /etc/letsencrypt/live/boorce.com/cert.pem -passout pass:
curl -v --insecure -u "${HP_USER}:${HP_PASSWORD}" "https://${HP_HOST}/Security/DeviceCertificates/NewCertWithPassword/Upload?fixed_response=true" --form certificate=@/root/certificate.pfx
cat /etc/letsencrypt/live/boorce.com/cert.pem /etc/letsencrypt/live/boorce.com/privkey.pem >/etc/letsencrypt/live/boorce.com/web.pem
