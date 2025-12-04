FROM bacula-director:trixie
RUN DEBIAN_FRONTEND=noninteractive apt install -yq --no-install-recommends bacula-sd bacula-fd apache2 php php-common php-cli libapache2-mod-php php-curl php-gd php-intl php-xml php-zip php-mbstring php-json php-bcmath php-mysqlnd php-ldap php-memcache sudo make && apt clean
RUN a2enmod rewrite
COPY bacula-gui-15.0.2.tar.gz /root
WORKDIR /root
RUN tar xzf bacula-gui-15.0.2.tar.gz && mkdir /var/www/html/baculum
WORKDIR /root/bacula-gui-15.0.2/baculum
RUN make DESTDIR=/ SAMPLETYPE=deb-template HTTPDNAME=apache2 HTTPDSITECONF=sites-available && a2ensite baculum-web && a2ensite baculum-api
RUN rmdir /usr/share/baculum/htdocs/protected/API/Config /usr/share/baculum/htdocs/protected/Web/Config && ln -s /etc/baculum/Config-api-apache/ /usr/share/baculum/htdocs/protected/API/Config && ln -s /etc/baculum/Config-web-apache/ /usr/share/baculum/htdocs/protected/Web/Config && chown -R www-data:www-data /etc/baculum/Config-api-apache/ /etc/baculum/Config-web-apache/ /usr/share/baculum/htdocs/
COPY www-data /etc/sudoers.d
RUN chmod 600 /etc/sudoers.d/www-data
ENV APACHE_RUN_USER www-data
ENV APACHE_RUN_GROUP www-data
ENV APACHE_LOG_DIR /var/log/apache2
ENTRYPOINT ["/usr/sbin/apachectl"]
CMD ["-D", "FOREGROUND"]
