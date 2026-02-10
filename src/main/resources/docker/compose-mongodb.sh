#!/bin/bash
#

# Clean Docker containers, if exists
sudo docker compose -f compose-mongodb.yml down --rmi local

# Clean unused Docker volumes
sudo docker volume prune -a -f

# Create Docker containers
sudo docker compose -f compose-mongodb.yml up -d

echo  "Waiting for mongodb to be ready"
sleep 30
