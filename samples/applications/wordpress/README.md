Wordpress Application
=====================
Wordpress application consists of a cartridge group which includes a MySQL cartridge and PHP cartridge. It defines a
startup dependency to start MySQL cluster first and PHP cluster second once the MySQL cluster is active.

Application folder structure
----------------------------
```
artifacts/<iaas>/ IaaS specific artifacts
scripts/common/ Common scripts for all iaases
scripts/<iaas> IaaS specific scripts
```

How to run
----------
```
cd scripts/<iaas>/
./deploy.sh
```