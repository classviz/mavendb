# Maven repository to DB

This application will scan all `maven` repos items and store them to database. Supported database
- `MySQL`
- `sqlite`
- `MongoDB`

## Prepare Database

A Docker Compose file has been configured
* [docker-compose.yml](docker-compose.yml)

Step 1. Config
- Modify the passwords set in the [.env](.env) file based on security requirements
- Modify the `innodb_buffer_pool_size` in [docker-compose.yml](docker-compose.yml) based on hardware

Step 2. Start
- For `Ubuntu`/`Linux` users
  - [Install Docker](https://docs.docker.com/engine/install/ubuntu/)
  - Execute script [docker-compose-run.sh](docker-compose-run.sh)
    - `./docker-compose-run.sh`
- For MacOS Users
  - [Install Docker Desktop](https://docs.docker.com/desktop/install/mac-install/)
  - Execute script [docker-compose-run.sh](docker-compose-run.sh)
    - `./docker-compose-run.sh`
- For Windows Users
  - [Install Docker Desktop](https://docs.docker.com/desktop/install/windows-install/)
  - Make sure the [docker memory resource limit](https://stackoverflow.com/questions/43460770/docker-windows-container-memory-limit) is bigger than the MySQL `innodb_buffer_pool_size`
    - Example: on a 64GB RM Windows laptop, set `--innodb_buffer_pool_size=24G` will work for maven central scan
  - Execute script [docker-compose-run.ps1](docker-compose-run.ps1)
    - `powershell -ExecutionPolicy Bypass -File .\docker-compose-run.ps1`

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
* Come to the `dist\etc` folder, edit the `config.properties` file
  * Modify the parameter `jakarta.persistence.jdbc.url` for the MySQL hostname
  * Modify the parameter `jakarta.persistence.jdbc.user` for the username
  * Modify the parameter `jakarta.persistence.jdbc.password` for the password
* Come to the `bin` folder, run either of the following commands
  * `bin $` `./run.sh file:///path/to/central-index/repo.maven.apache.org/maven2/.index/`

## Exeuction Time

- Since maven central artifacts is keep improving, so the runtime will be longer and longer

|  Time    | artifacts count  | Runtime/hour  | Notes |
|----------|------------------|---------------|-------|
| Sep 2023 |    `44,758,974`  |         `5.6` | innodb_buffer_pool_size=40G
| Jul 2025 |    `76,619,430`  |        `19.1` | innodb_buffer_pool_size=100G
| Aug 2025 |    `76,638,341`  |        `18.8` | innodb_buffer_pool_size=100G, `61,164,426` + `6,608,605`


## Access

### Adminer

Access via DB Adminer: [http://localhost:10191/](http://localhost:10191/)
- Username: `mavendbadmin`, as defined in [.env](.env) file
- Password: use the password in [.env](.env) file

### REST API

Access via REST API
- Rest API user guide see [php-crud-api#treeql](https://github.com/mevdschee/php-crud-api#treeql-a-pragmatic-graphql)
- Sample: [http://localhost:2080/api.php/records/gav?filter=group_id,eq,org.apache.commons&filter=artifact_id,eq,commons-lang3&size=10](http://localhost:2080/api.php/records/gav?filter=group_id,eq,org.apache.commons&filter=artifact_id,eq,commons-lang3&size=10)
  - `group_id`: `org.apache.commons`
  - `artifact_id`: `commons-lang3`

### Docker Shell

MySQL Docker Container
- Come into Container
```
host $ sudo docker compose exec -it mavendb-mysql bash
```

- Login to MySQL, use the password defined in [.env](.env) file 
```
container bash-5.1# mysql -p
```

- Dump table, which need seeral minutes
  - [export.sql for sqlite](src/main/resources/db/sqlite/export.sql)
  - `name` colum may have `new line` character, we replace it with `space`
  - `description` column is skipped for now

```
container bash-5.1# pwd && ls -alh
/var/lib/mysql-files
total 26G

-rw-r----- 1 mysql mysql 9.3M Jul 31 2025 23:48 g.csv
-rw-r----- 1 mysql mysql  50M Jul 31 2025 23:49 ga.csv
-rw-r----- 1 mysql mysql  26G Jul 31 2025 23:56 gav.csv
```

Copy files out
```
host $ sudo docker cp mavendb-mysql:/var/lib/mysql-files/ dist
```

Import to sqlite

- Init
```
sqlite3 mavendb.sqlite
.read create.sql
```

- Import
```
.mode csv
.import g.csv g
.import ga.csv ga
.import gav.csv gav
```

- Index
```
.read index.sql
```


## Internal Only


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
