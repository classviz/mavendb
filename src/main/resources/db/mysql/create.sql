CREATE SCHEMA IF NOT EXISTS `mavendb` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin ;
USE `mavendb`;


DROP TABLE IF exists `gav`;
CREATE TABLE         `gav` (
  `seqid`                       bigint                                 NOT NULL COMMENT 'Squence ID',

  `major_version`                   int                            DEFAULT NULL COMMENT 'VersionAnalyser.getMajorVersion()',
  `version_seq`                  bigint                                NOT NULL COMMENT 'VersionAnalyser.getVersionSeq()',

  `record_modified`              bigint                            DEFAULT NULL COMMENT 'Record.recordModified',
  `file_modified`                bigint                            DEFAULT NULL COMMENT 'Record.fileModified',
  `file_size`                    bigint                            DEFAULT NULL COMMENT 'Record.fileSize',

  `has_signature`               BOOLEAN                            DEFAULT NULL COMMENT 'Record.hasSignature',
  `has_sources`                 BOOLEAN                            DEFAULT NULL COMMENT 'Record.hasSources',
  `has_javadoc`                 BOOLEAN                            DEFAULT NULL COMMENT 'Record.hasJavadoc',

  `sha1`                           char( 40)   COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Record.sha1',                                    -- 2026.02  Max        106

  `group_id`                    varchar(256)   COLLATE utf8mb4_bin     NOT NULL COMMENT 'Record.groupId',                                 -- 2026.02  Max        129
  `artifact_id`                 varchar(256)   COLLATE utf8mb4_bin     NOT NULL COMMENT 'Record.artifactId',                              -- 2026.02  Max         98
  `artifact_version`            varchar(128)   COLLATE utf8mb4_bin     NOT NULL COMMENT 'Record.version',                                 -- 2026.02  Max        118

  `classifier`                  varchar( 128)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Record.classifier',                              -- 2026.02  Max         67
  `packaging`                   varchar( 256)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Record.packaging',                               -- 2023.02  Max        113
  `file_extension`              varchar( 256)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Record.fileExtension',                           -- 2026.02  Max        113
  `file_name`                   varchar( 256)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'artifactId-version[-classifier].fileExtension',  -- 2026.02  Max         ??

  `name`                        varchar(1024)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Record.name',                                    -- 2026.02  Max        486

  `bundle_description`          varchar(4096)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Bundle-Description',                             -- 2026.02  Max      2,503
  `bundle_docurl`               varchar( 512)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Bundle-DocURL',                                  -- 2026.02  Max        221
  `bundle_license`              varchar(1024)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Bundle-License',                                 -- 2026.02  Max        463
  `bundle_name`                 varchar( 512)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Bundle-Name',                                    -- 2026.02  Max        155
  `bundle_symbolicname`         varchar( 512)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Bundle-SymbolicName',                            -- 2026.02  Max        179
  `bundle_version`              varchar( 256)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Bundle-Version',                                 -- 2026.02  Max        122

  `require_bundle`              varchar(8196)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Require-Bundle',                                 -- 2026.02  Max      3,245
  `export_service`              varchar(8196)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Export-Service',                                 -- 2026.02  Max      3,529

  `export_package`              MEDIUMTEXT     COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Export-Package',                                 -- 2026.02  Max  1,247,534
  `import_package`              MEDIUMTEXT     COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Import-Package',                                 -- 2026.02  Max     87,015

  `description`                 MEDIUMTEXT     COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Record.description',                             -- 2026.02  Max     53,217

  `json`                           json                           DEFAULT NULL COMMENT 'Other fields other than above'                    -- 2026.02  Max  1,263,656

) ENGINE=InnoDB COLLATE=utf8mb4_bin COMMENT='Groups Artifact Version';


DROP TABLE IF exists `g`;
CREATE TABLE         `g` (
  `group_id`                    varchar(254)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'From gav.group_id',

  `artifact_version_counter`        int                           DEFAULT NULL,
  `major_version_counter`           int                           DEFAULT NULL,
  `version_seq_max`              bigint                           DEFAULT NULL,
  `file_modified_max`            bigint                           DEFAULT NULL,
  `group_id_left1`              varchar(128) COLLATE utf8mb4_bin GENERATED ALWAYS AS (substring_index(`group_id`,'.',1)) VIRTUAL,
  `group_id_left2`              varchar(256) COLLATE utf8mb4_bin GENERATED ALWAYS AS (substring_index(`group_id`,'.',2)) VIRTUAL,
  `group_id_left3`              varchar(256) COLLATE utf8mb4_bin GENERATED ALWAYS AS (substring_index(`group_id`,'.',3)) VIRTUAL,
  `group_id_left4`              varchar(256) COLLATE utf8mb4_bin GENERATED ALWAYS AS (substring_index(`group_id`,'.',4)) VIRTUAL,

  PRIMARY KEY (`group_id`),
  KEY `index_group_id_left1` (`group_id_left1`),
  KEY `index_group_id_left2` (`group_id_left2`)
) ENGINE=InnoDB COLLATE=utf8mb4_bin COMMENT='Groups';


DROP TABLE IF exists `ga`;
CREATE TABLE         `ga` (
  `group_id`                    varchar(256)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'From gav.group_id',
  `artifact_id`                 varchar(256)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'From gav.artifact_id',

  `artifact_version_counter`        int                           DEFAULT NULL,
  `major_version_counter`           int                           DEFAULT NULL,
  `version_seq_max`              bigint                           DEFAULT NULL,
  `file_modified_max`            bigint                           DEFAULT NULL,

  PRIMARY KEY (`group_id`,`artifact_id`)
) ENGINE=InnoDB COLLATE=utf8mb4_bin COMMENT='Groups Artifact';


--
-- Views
--

DROP VIEW IF EXISTS v_gav;
CREATE VIEW         v_gav AS
SELECT
    group_id,
    artifact_id,
    artifact_version,
    major_version,
    version_seq,

    classifier,
    packaging,

    file_name,
    file_size,
    file_extension,
    file_modified,
    record_modified,

    has_signature,
    has_sources,
    has_javadoc,

    concat('mvn dependency:copy -U -DoutputDirectory=. -Dartifact=',
      if(isnull(classifier),
        concat(group_id, ':', artifact_id, ':', artifact_version,':', file_extension),
        concat(group_id, ':', artifact_id, ':', artifact_version,':', file_extension, ':', classifier)
      ))                                                 AS mvn_command,

    name
FROM gav;
