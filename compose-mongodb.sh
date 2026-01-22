#!/bin/bash
#

# Clean Docker containers, if exists
sudo docker compose -f compose-mongodb.yml down --rmi local

# Create Docker containers
sudo docker compose -f compose-mongodb.yml up -d

echo  "Waiting for mongodb to be ready"
sleep 30
