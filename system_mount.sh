#!/bin/sh

for i in `ls /mnt/Freebox/winiso/*.iso`
do
    ISONAME=`basename $i|sed -e 's/\.iso//g'`
    if [ ! -d /var/www/html/pxe/${ISONAME} ]
    then
	    sudo mkdir /var/www/html/pxe/${ISONAME}
    fi
    if [ ! -e /lib/systemd/system/var-www-html-pxe-${ISONAME}.mount ]
    then
        sudo sed -e "s/%ISONAME%/${ISONAME}/g" mnt-TEMPLATE.mount >/lib/systemd/system/var-www-html-pxe-${ISONAME}.mount
        sudo systemctl enable var-www-html-pxe-${ISONAME}.mount
        sudo systemctl start var-www-html-pxe-${ISONAME}.mount 
    fi
done
