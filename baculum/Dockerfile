FROM bacula-base:latest
RUN DEBIAN_FRONTEND=noninteractive apt install -yq --no-install-recommends bacula-console bacula-common-mysql bacula-director-mysql bacula-director bacula-sd bacula-fd apache2 php php-cli libapache2-mod-php php-curl php-gd php-intl php-memcache php-xml php-zip php-mbstring php-json php-bcmath php-mysqlnd php-ldap sudo make
RUN a2enmod rewrite
COPY baculum-9.6.6.3.tar.gz /root
WORKDIR /root
RUN tar xzf baculum-9.6.6.3.tar.gz
RUN mkdir /var/www/html/baculum
WORKDIR /root/bacula-gui-9.6.6.3/baculum
RUN make DESTDIR=/ SAMPLETYPE=deb-template HTTPDNAME=apache2 HTTPDSITECONF=sites-available
RUN a2ensite baculum-web
RUN a2ensite baculum-api
RUN rmdir /usr/share/baculum/htdocs/protected/API/Config
RUN ln -s /etc/baculum/Config-api-apache/ /usr/share/baculum/htdocs/protected/API/Config
RUN rmdir /usr/share/baculum/htdocs/protected/Web/Config
RUN ln -s /etc/baculum/Config-web-apache/ /usr/share/baculum/htdocs/protected/Web/Config
RUN chown -R www-data:www-data /etc/baculum/Config-api-apache/ /etc/baculum/Config-web-apache/ /usr/share/baculum/htdocs/
COPY www-data /etc/sudoers.d
RUN chmod 600 /etc/sudoers.d/www-data
ENV APACHE_RUN_USER www-data
ENV APACHE_RUN_GROUP www-data
ENV APACHE_LOG_DIR /var/log/apache2
ENTRYPOINT ["apachectl "]
CMD ["-D", "FOREGROUND"]
