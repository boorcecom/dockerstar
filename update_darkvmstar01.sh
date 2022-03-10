#!/bin/sh

openssl pkcs12 -export -out /root/certificate.pfx -inkey /etc/letsencrypt/live/boorce.com/privkey.pem -in /etc/letsencrypt/live/boorce.com/cert.pem -passout pass:
curl -v --insecure -H "Authorization: Basic YWRtaW46Wm9yZ2x1YkAwMQ==" https://hp7740.ad.boorce.com/Security/DeviceCertificates/NewCertWithPassword/Upload?fixed_response=true --form certificate=@/root/certificate.pfx
cat /etc/letsencrypt/live/boorce.com/cert.pem /etc/letsencrypt/live/boorce.com/privkey.pem >/etc/letsencrypt/live/boorce.com/web.pem
