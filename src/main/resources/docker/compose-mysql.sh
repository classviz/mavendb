#!/bin/bash
#

# Clean Docker containers, if exists
sudo docker compose -f compose-mysql.yml down --rmi local
sudo rm -rf mysql-files

# Clean unused Docker volumes
sudo docker volume prune -a -f

# Create Docker containers
sudo docker compose -f compose-mysql.yml up -d

echo  "Waiting for mysql to be ready"
sleep 30
