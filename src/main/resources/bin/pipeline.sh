#!/bin/sh
#

# Refresh the docker databases, then load the maven index into the given database.
# Usage: run_pipeline <db_type>   (mysql, psql, mongodb, sqlite)
run_pipeline() {
    dbtype="$1"

    cd ../docker
    pwd
    ./docker-refresh.sh

    sleep 30

    cd ../bin
    pwd
    ./run.sh file:///home/amos/develop/mavencentral/repo.maven.apache.org/maven2/.index/ "$dbtype"
}

run_pipeline mysql
run_pipeline psql
run_pipeline mongodb
run_pipeline sqlite
