
# Remove a file or folder quietly
# Like linux "rm -rf"
function quiet_rm($item)
{
  if (Test-Path $item) {
    echo "  Removing $item"
    Remove-Item $item  -r -force
  }
}

# Clean Docker containers, if exists
docker compose down --rmi local

quiet_rm mysql-data
quiet_rm mysql-files

# Create Docker containers
docker compose up -d

echo  "Waiting for mysql to be ready"
sleep 60
