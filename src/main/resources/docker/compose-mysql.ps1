
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
docker compose -f compose-mysql.yml down --rmi local

quiet_rm mysql-files

# Create Docker containers
docker compose -f compose-mysql.yml up -d

echo  "Waiting for mysql to be ready"
sleep 60
