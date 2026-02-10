# Maven repository to DB

This application will scan all `maven` repos items and store them to database. Supported database
- `MongoDB`
- `MySQL`
- `PostgreSQL`
- `SQLite`

## Prepare Database

### Option: MongoDB

Execute script [compose-mongodb.sh](src/main/resources/docker/compose-mongodb.sh)
- `./compose-mongodb.sh`


### Option: MySQL

A Docker Compose file has been configured
* [compose-mysql.yml](src/main/resources/docker/compose-mysql.yml)

Step 1. Config
- Modify the passwords set in the [.env](src/main/resources/docker/.env) file based on security requirements
- Modify the `innodb_buffer_pool_size` in [docker-compose.yml](docker-compose.yml) based on hardware

Step 2. Start
- For `Ubuntu`/`Linux` users
  - [Install Docker](https://docs.docker.com/engine/install/ubuntu/)
  - Execute script [compose-mysql.sh](src/main/resources/docker/compose-mysql.sh)
    - `./compose-mysql.sh`
- For MacOS Users
  - [Install Docker Desktop](https://docs.docker.com/desktop/install/mac-install/)
  - Execute script [compose-mysql.sh](src/main/resources/docker/compose-mysql.sh)
    - `./compose-mysql.sh`
- For Windows Users
  - [Install Docker Desktop](https://docs.docker.com/desktop/install/windows-install/)
  - Make sure the [docker memory resource limit](https://stackoverflow.com/questions/43460770/docker-windows-container-memory-limit) is bigger than the MySQL `innodb_buffer_pool_size`
    - Example: on a 64GB RM Windows laptop, set `--innodb_buffer_pool_size=24G` will work for maven central scan
  - Execute script [compose-mysql.ps1](src/main/resources/docker/compose-mysql.ps1)
    - `powershell -ExecutionPolicy Bypass -File .\compose-mysql.ps1`

### Option: PSQL

Execute script [compose-psql.sh](src/main/resources/docker/compose-psql.sh)
- `./compose-psql.sh`

### Option: SQLite

No setup required! SQLite stores everything in a single file.

- Default database file: `mavendb.sqlite` (created in the current directory)
- Configure the path in [config.properties](src/main/resources/etc/config.properties):
  ```properties
  org.mavendb.sqlite.url=jdbc:sqlite:/path/to/mavendb.sqlite
  ```


## Download Indexes

Download index files of the repo

- `wget -r -nc -l1 --no-parent https://repo.maven.apache.org/maven2/.index/`
  - -r (or --recursive): Turns on recursive retrieving of files.
  - -nc (or --no-clobber): skip those that already exist locally, use the 
  - -l1 (or --level=1): Sets the maximum recursion depth to 1. This means it will only download files in the immediate directory specified by the URL and will not follow links into subdirectories or other parts of the website.
  - --no-parent: Ensures that wget does not ascend to the parent directory of the specified URL, keeping the download contained within the target directory.

```
Jan 2026

Total wall clock time: 8m 34s
Downloaded: 2114 files, 7.9G in 2m 34s (52.7 MB/s)
```


## Build and Run

Requriments

* OpenJDK `25` or later
* Maven `3.9.3` or later

Build the Source Code
* `./build.sh`

How to Run the Tool
* Come to the `dist` folder
  * Unzip the file `mavendb-<version>-<timestampe>-bin.zip`
* (Optional) Come to the `etc` edit the `config.properties` file
  * The default values has been optimized, well we can still modify it when needed
* Come to the `bin` folder, run either of the following commands

```sh
bin $ ./run.sh file:///path/to/central-index/repo.maven.apache.org/maven2/.index/ mongodb
bin $ ./run.sh file:///path/to/central-index/repo.maven.apache.org/maven2/.index/ mysql
bin $ ./run.sh file:///path/to/central-index/repo.maven.apache.org/maven2/.index/ psql
bin $ ./run.sh file:///path/to/central-index/repo.maven.apache.org/maven2/.index/ sqlite
```


## Exeuction Time

The following is the currrent execution time.
- Since maven central artifacts is keep improving, so the runtime will be longer and longer

|  Time    | artifacts count  | Runtime     | DB Type | Notes                                 |
|----------|-----------------:|------------:|---------|---------------------------------------|
| Sep 2023 |    `44,758,974`  |  `5.6` hour | MySQL   | innodb_buffer_pool_size=40G
| Jul 2025 |    `76,619,430`  | `19.1` hour | MySQL   | innodb_buffer_pool_size=100G
| Aug 2025 |    `76,638,341`  | `18.8` hour | MySQL   | `61,164,426` + `6,608,605`
| Feb 2026 |    `89,587,849`  |  `1.7` hour | MySQL   | `4,061,407` + `1,980,766`, 50k batch
| Feb 2026 |    `89,587,849`  |   `37` min  | PSQL    | `1,347,975` + `882,647`, 50k batch
| Feb 2026 |    `89,587,849`  | `26.6` min  | Mongodb | `1,169,402` + `428,064`
| Feb 2026 |    `89,587,849`  | `40.2` min  | SQLite  | `1,317,668` + `479,457`, 50k batch


## Access

### Mongo Express

Local Mongo Express: [http://localhost:8081/](http://localhost:8081/)
- Username: `root`
- Password: use the password in [.env](src/main/resources/docker/.env) file


### MySQL

Access via DB Adminer: [http://localhost:10191/](http://localhost:10191/)
- Username: `mavendbadmin`, as defined in [.env](src/main/resources/docker/.env) file
- Password: use the password in [.env](src/main/resources/docker/.env) file

Access via REST API
- Rest API user guide see [php-crud-api#treeql](https://github.com/mevdschee/php-crud-api#treeql-a-pragmatic-graphql)
- Sample: [http://localhost:2080/api.php/records/gav?filter=group_id,eq,org.apache.commons&filter=artifact_id,eq,commons-lang3&size=10](http://localhost:2080/api.php/records/gav?filter=group_id,eq,org.apache.commons&filter=artifact_id,eq,commons-lang3&size=10)
  - `group_id`: `org.apache.commons`
  - `artifact_id`: `commons-lang3`


Access via Docker Shell

- Come into Container
```
host $ sudo docker compose exec -it mavendb-mysql bash
```

- Login to MySQL, use the password defined in [.env](src/main/resources/docker/.env) file
```
container bash-5.1# mysql -p
```


### PSQL

Access via DB Adminer: [http://localhost:10192/](http://localhost:10192/)
- Username: `root`
- Password: use the password in [.env](src/main/resources/docker/.env) file


## Internal Only

### Max Lengths

Max length of the text fields of maven central repository.

```
sha1=106

groupId=129
artifactId=98
version=118
classifier=67
packaging=113
fileExtension=113
name=486
description=53217

Bundle-Description=2503
Bundle-DocURL=221
Bundle-License=463
Bundle-Name=155
Bundle-SymbolicName=179
Bundle-Version=122

Export-Package=1247534
Export-Service=3529
Import-Package=87015
Require-Bundle=3245

repositoryId=7
```


### Publish Site

Maven Settings
* Edit `conf/settings.xml`
* Add Server section, where
  * `username` is the github login user
  * `password` is the github user's token

```
<server>
  <id>github.com</id>
  <username></username>
  <password></password>
</server>
```

Publish site
* `mvn clean site site:stage scm-publish:publish-scm`


### Sample Data

Size

```
indexRepoId=central
indexLastPublished=Thu Jan 15 04:26:25 PST 2026
isIncremental=false
indexRequiredChunkNames=[nexus-maven-repository-index.gz]
chunkName=nexus-maven-repository-index.gz
chunkVersion=1
chunkPublished=Thu Jan 15 04:26:25 PST 2026
Chunk stats:

ALL_GROUPS = 1
ARTIFACT_ADD = 89587846
ROOT_GROUPS = 1
DESCRIPTOR = 1
```

`org.apache.maven.index.reader.Record`

```
record=Record{
  type=ARTIFACT_ADD,
  expanded={
      Key{name='Bundle-License', type=String}=https://www.apache.org/licenses/LICENSE-2.0.txt, 
      Key{name='version', type=String}=17-0.9.2, 
      Key{name='groupId', type=String}=us.ihmc, 
      Key{name='Bundle-Name', type=String}=sourceJar, 
      Key{name='packaging', type=String}=jar, 
      Key{name='description', type=String}=SCS2 Simulation, 
      Key{name='hasJavadoc', type=Boolean}=false, 
      Key{name='sha1', type=String}=8a16ffef75fef5f5c46d4290ef126ac59f71fcf9, 
      Key{name='recordModified', type=Long}=1765382166548, 
      Key{name='fileSize', type=Long}=229462, 
      Key{name='Bundle-Version', type=String}=17-0.9.2, 
      Key{name='fileExtension', type=String}=jar, 
      Key{name='classifier', type=String}=sources, 
      Key{name='name', type=String}=scs2-simulation, 
      Key{name='artifactId', type=String}=scs2-simulation, 
      Key{name='hasSources', type=Boolean}=false, 
      Key{name='hasSignature', type=Boolean}=false, 
      Key{name='fileModified', type=Long}=1657893876000
  }
}
```

```
Key{name='version', type=String}=2.7.15.0, name=version, type=String
Key{name='groupId', type=String}=xyz.opcal.cloud, name=groupId, type=String
Key{name='packaging', type=String}=jar, name=packaging, type=String
Key{name='description', type=String}=logback api for webflux request, name=description, type=String
Key{name='hasJavadoc', type=Boolean}=false, name=hasJavadoc, type=Boolean
Key{name='sha1', type=String}=2cb6eeb2b4e0bd77fd00f661d69b69db4ff098ad, name=sha1, type=String
Key{name='recordModified', type=Long}=1765379584124, name=recordModified, type=Long
Key{name='fileSize', type=Long}=371874, name=fileSize, type=Long
Key{name='fileExtension', type=String}=jar, name=fileExtension, type=String
Key{name='classifier', type=String}=javadoc, name=classifier, type=String
Key{name='name', type=String}=opcal-cloud-commons-logback-webflux, name=name, type=String
Key{name='artifactId', type=String}=opcal-cloud-commons-logback-webflux, name=artifactId, type=String
Key{name='hasSources', type=Boolean}=false, name=hasSources, type=Boolean
Key{name='hasSignature', type=Boolean}=true, name=hasSignature, type=Boolean
Key{name='fileModified', type=Long}=1692943727000, name=fileModified, type=Long
```

```json
{
  "_id": 13535,
  "Bundle-Description": "JaCoCo Core",
  "Bundle-DocURL": "https://www.absa.africa",
  "Bundle-License": "https://www.eclipse.org/legal/epl-2.0/",
  "Bundle-Name": "JaCoCo Core",
  "Bundle-SymbolicName": "za.co.absa.jacoco.org.jacoco.core",
  "Bundle-Version": "0.8.10.202305020106",
  "Export-Package": "org.jacoco.core.internal;x-internal:=true;version=\"0.8.10\",org.jacoco.core.internal.analysis;x-internal:=true;version=\"0.8.10\";uses:=\"org.jacoco.core.analysis,org.jacoco.core.internal.analysis.filter,org.jacoco.core.internal.flow,org.objectweb.asm,org.objectweb.asm.tree\",org.jacoco.core.internal.analysis.filter;x-internal:=true;version=\"0.8.10\";uses:=\"org.objectweb.asm.tree\",org.jacoco.core.internal.data;x-internal:=true;version=\"0.8.10\",org.jacoco.core.internal.flow;x-internal:=true;version=\"0.8.10\";uses:=\"org.jacoco.core.internal.analysis,org.objectweb.asm,org.objectweb.asm.commons,org.objectweb.asm.tree\",org.jacoco.core.internal.instr;x-internal:=true;version=\"0.8.10\";uses:=\"org.jacoco.core.internal.flow,org.jacoco.core.runtime,org.objectweb.asm\",org.jacoco.core;version=\"0.8.10\",org.jacoco.core.analysis;version=\"0.8.10\";uses:=\"org.jacoco.core.data,org.jacoco.core.internal.analysis\",org.jacoco.core.data;version=\"0.8.10\";uses:=\"org.jacoco.core.internal.data\",org.jacoco.core.instr;version=\"0.8.10\";uses:=\"org.jacoco.core.runtime\",org.jacoco.core.runtime;version=\"0.8.10\";uses:=\"org.jacoco.core.data,org.objectweb.asm\",org.jacoco.core.tools;version=\"0.8.10\";uses:=\"org.jacoco.core.data",
  "Import-Package": "org.jacoco.core;version=\"[0.8.10,0.8.11)\",org.jacoco.core.analysis;version=\"[0.8.10,0.8.11)\",org.jacoco.core.data;version=\"[0.8.10,0.8.11)\",org.jacoco.core.internal;version=\"[0.8.10,0.8.11)\",org.jacoco.core.internal.analysis;version=\"[0.8.10,0.8.11)\",org.jacoco.core.internal.analysis.filter;version=\"[0.8.10,0.8.11)\",org.jacoco.core.internal.data;version=\"[0.8.10,0.8.11)\",org.jacoco.core.internal.flow;version=\"[0.8.10,0.8.11)\",org.jacoco.core.internal.instr;version=\"[0.8.10,0.8.11)\",org.jacoco.core.runtime;version=\"[0.8.10,0.8.11)\",org.objectweb.asm;version=\"[9.5.0,9.6)\",org.objectweb.asm.commons;version=\"[9.5.0,9.6)\",org.objectweb.asm.tree;version=\"[9.5.0,9.6)"
}
```
