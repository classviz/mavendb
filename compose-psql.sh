#!/bin/bash
#

# Clean Docker containers, if exists
sudo docker compose -f compose-psql.yml down --rmi local

# Clean unused Docker volumes
sudo docker volume prune -a -f

# Create Docker containers
sudo docker compose -f compose-psql.yml up -d

echo  "Waiting for postgresql to be ready"
sleep 30
