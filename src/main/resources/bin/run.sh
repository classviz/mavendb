#!/bin/bash
#
# Script to trigger sync the latest central maven index to a local database.
#
# For MySQL database, need to install mysql client:
#   sudo apt install mysql-client
#
# Usage:
#   ./run.sh <path_to_repos_folder> <db_type>
# Example:
#   ./run.sh file:///path/to/central-index/repo.maven.apache.org/maven2/.index/ mongodb
#   ./run.sh file:///path/to/central-index/repo.maven.apache.org/maven2/.index/ mysql
#   ./run.sh file:///path/to/central-index/repo.maven.apache.org/maven2/.index/ psql
#   ./run.sh file:///path/to/central-index/repo.maven.apache.org/maven2/.index/ sqlite
#

# Function
timestamp() {
  retval=$(date '+%Y-%m-%d %T.%3N')
  echo $(basename $0) $retval
}

if [ $# -eq 0 ]; then
  echo "$0 $(timestamp) No arguments supplied. The repos folder and dbtype are expected."
  exit 1
fi

# Get current directory
BASEDIR="$( cd "$(dirname "$0")" ; pwd -P )"
echo "$(timestamp) Base Directory is $BASEDIR"
echo ""


# Set JAVA_OPTS

JAVA_OPTS=" \
 -showversion \
 -verbose:module \
 -Xdiag \
 -Xlog:codecache,gc*,safepoint:file=../log/jvmunified.log:level,tags,time,uptime,pid:filesize=209715200,filecount=10 \
 -XshowSettings:all \
 -XX:+UnlockDiagnosticVMOptions \
 -XX:NativeMemoryTracking=summary \
 -XX:+ExtensiveErrorReports \
 -XX:+HeapDumpOnOutOfMemoryError \
 -XX:+PerfDataSaveToFile \
 -XX:+PrintClassHistogram \
 -XX:+PrintCommandLineFlags \
 -XX:+PrintConcurrentLocks \
 -XX:+PrintNMTStatistics \
 -XX:+DebugNonSafepoints \
 -XX:FlightRecorderOptions=repository=../log \
 -XX:StartFlightRecording=disk=true,dumponexit=true,filename=../log/profile.jfr,name=Profiling,settings=profile \
"

RUN_CMD="java $JAVA_OPTS -Xmx32g -server -jar $BASEDIR/../mavendb.jar -f $1 -d $2"
echo "$(timestamp) $RUN_CMD"
eval               $RUN_CMD

if [ "$2" = "sqlite" ]; then
  zip ../var/mavendb.sqlite.zip mavendb.sqlite

elif [ "$2" = "psql" ]; then
  sudo docker exec -t mavendb-psql pg_dump -U mavendbadmin mavendb | gzip > ../var/mavendb-psql.sql.gz

  # Export tables to CSV
  PGPASSWORD='123456' psql -h localhost -U mavendbadmin -d mavendb -c "\copy (SELECT * FROM mavendb.g)     TO 'g.csv'   WITH (FORMAT CSV, HEADER);"
  PGPASSWORD='123456' psql -h localhost -U mavendbadmin -d mavendb -c "\copy (SELECT * FROM mavendb.ga)    TO 'ga.csv'  WITH (FORMAT CSV, HEADER);"
  PGPASSWORD='123456' psql -h localhost -U mavendbadmin -d mavendb -c "\copy (SELECT * FROM mavendb.v_gav) TO 'gav.csv' WITH (FORMAT CSV, HEADER);"
  mv *.csv ../var/

elif [ "$2" = "mysql" ]; then
  # Here we use the root user to avoid the following error when dumping tablespaces:
  # mysqldump: Error: 'Access denied; you need (at least one of) the PROCESS privilege(s) for this operation' when trying to dump tablespaces
  rm -f ~/.my.cnf
  touch ~/.my.cnf
  printf "[client]\nuser=%s\npassword=%s\n" "root" "123456" >> ~/.my.cnf

  mysqldump --host=127.0.0.1 --port=3306 mavendb | gzip > ../var/mavendb-mysql.sql.gz

elif [ "$2" = "mongodb" ]; then
  sudo docker exec -t mavendb-mongo mongodump --host mavendb-mongo --username root --password 123456 --authenticationDatabase admin --authenticationMechanism SCRAM-SHA-256 --db mavendb --archive | gzip > ../var/mavendb-mongo.archive.gz

else
  echo "$(timestamp) Invalid db type: $2. Expected sqlite, psql, mysql, or mongodb."
  exit 1
fi


echo "Finished"
