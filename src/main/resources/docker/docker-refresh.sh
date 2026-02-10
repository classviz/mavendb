#!/bin/bash
#
# Refresh the docker images for the databases.
# This is useful when you have made changes to the compose files and want to rebuild the images.
#

./compose-mongodb.sh
./compose-mysql.sh
./compose-psql.sh