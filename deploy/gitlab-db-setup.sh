#!/usr/bin/env bash

set -xeuo pipefail

MARIADB_COMMAND=$1
HOST=$2

if [ "$MARIADB_COMMAND" = "mariadb" ]; then
    MARIADB=mariadb
else
    MARIADB="docker run -i --rm --link $HOST mariadb:10.4 mariadb"
fi

# If using local mariadb and mariadbadmin is also installed locally use it
if [[ "$MARIADB_COMMAND" == "mariadb" ]] && command -v mariadb-admin &> /dev/null;
then
    MARIADBADMIN=mariadb-admin
else
    MARIADBADMIN="docker run -i --rm --link $HOST mariadb:10.4 mariadbadmin"
fi

until $MARIADBADMIN ping --skip-ssl --silent --protocol=TCP -h $HOST -P 3306 -u root -proot; do echo waiting for mariadb; sleep 1; done

$MARIADB --skip-ssl -v -h $HOST -u root -proot <<EOF
CREATE USER 'tuf_repo' identified by 'tuf_repo' ;
CREATE DATABASE tuf_repo ;
GRANT ALL PRIVILEGES ON \`tuf_repo%\`.* TO 'tuf_repo'@'%' ;

CREATE USER 'tuf_keyserver' identified by 'tuf_keyserver' ;
CREATE DATABASE tuf_keyserver ;
GRANT ALL PRIVILEGES ON \`tuf_keyserver%\`.* TO 'tuf_keyserver'@'%' ;

GRANT ALL PRIVILEGES ON ota_tuf.* TO 'tuf_repo'@'%' ;
GRANT ALL PRIVILEGES ON ota_tuf.* TO 'tuf_keyserver'@'%' ;

FLUSH PRIVILEGES

EOF
