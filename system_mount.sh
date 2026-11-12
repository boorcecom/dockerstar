#!/bin/sh
set -x
for i in `ls /mnt/Freebox/winiso/*.iso`
do
    ISONAME=`basename $i|sed -e 's/\.iso//g'`
    if [ ! -d /opt/pxe/${ISONAME} ]
    then
	    sudo mkdir /opt/pxe/${ISONAME}
    fi
    if [ ! -e /lib/systemd/system/opt-pxe-${ISONAME}.mount ]
    then
        sudo sed -e "s/%ISONAME%/${ISONAME}/g" mnt-TEMPLATE.mount >/lib/systemd/system/opt-pxe-${ISONAME}.mount
        sudo systemctl enable opt-pxe-${ISONAME}.mount
        sudo systemctl start opt-pxe-${ISONAME}.mount 
    fi
done
