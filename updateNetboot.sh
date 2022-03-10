#!/bin/sh
cd /opt/pihole_storage/var-lib-tftpboot/
mv netboot.xyz.kpxe netboot.xyz.kpxe.old
mv netboot.xyz.efi netboot.xyz.efi.old
wget https://boot.netboot.xyz/ipxe/netboot.xyz.kpxe
wget https://boot.netboot.xyz/ipxe/netboot.xyz.efi
