#!/bin/bash
#

sudo docker compose -f compose-mongodb.yml restart
sudo docker compose -f compose-mysql.yml   restart
sudo docker compose -f compose-psql.yml    restart
