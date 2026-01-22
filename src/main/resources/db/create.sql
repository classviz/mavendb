CREATE SCHEMA IF NOT EXISTS `mavendb` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin ;
USE `mavendb`;


DROP TABLE IF exists `record`;
CREATE TABLE `record` (
  `seqid`                        bigint                           NOT NULL     COMMENT 'Squence ID',

  `major_version`                   int                           DEFAULT NULL COMMENT 'Generated from `artifact_version`, the most left part of the version',
  `version_seq`                  bigint      NOT NULL             DEFAULT '0'  COMMENT 'Generated from `artifact_version`, sequence number for the version',
  `json`                           json                           DEFAULT NULL COMMENT 'toJson(ArtifactInfo)',                             -- 2023.01.01  Max 54,930

  PRIMARY KEY (`seqid`)
) ENGINE=InnoDB COLLATE=utf8mb4_bin COMMENT 'Maven Repos Record, from https://github.com/apache/maven-indexer/blob/master/indexer-reader/src/main/java/org/apache/maven/index/reader/Record.java';


DROP TABLE IF exists `gav`;
CREATE TABLE         `gav` (
  `seqid`                       bigint                                NOT NULL COMMENT 'From [record]-`seqid`',

  `group_id`                    varchar(254)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'From [record]-`json->>"$.groupId"`',         -- 2023.02.12  Max 93
  `artifact_id`                 varchar(254)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'From [record]-`json->>"$.artifactId"`',      -- 2023.02.12  Max 87
  `artifact_version`            varchar(128)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'From [record]-`json->>"$.version"`',         -- 2023.02.12  Max 94

  `file_name`                   varchar(512)  COLLATE utf8mb4_bin  GENERATED ALWAYS AS (LEFT( if( isnull(classifier),
                                                                      concat(artifact_id, '-', artifact_version, '.', file_extension),
                                                                      concat(artifact_id, '-', artifact_version, '-', classifier, '.', file_extension)), 512)) virtual
                                                                      NOT NULL COMMENT 'MVN file name',

  `major_version`                   int                           DEFAULT NULL COMMENT 'From [record]-`major_version`',
  `version_seq`                  bigint                               NOT NULL COMMENT 'From [record]-`version_seq`',

  `last_modified`              DATETIME                           DEFAULT NULL COMMENT 'From [record]-`json->>"$.lastModified"`',
  `size`                         bigint                           DEFAULT NULL COMMENT 'From [record]-`json->>"$.size"`',
  `sha1`                           char(  40) COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'From [record]-`json->>"$.sha1"`',

  `signature_exists`                int                           DEFAULT NULL COMMENT 'From [record]-`json->>"$.signatureExists"` or From [record]-`signature_exists`, Values: NOT_PRESENT(0), PRESENT(1), NOT_AVAILABLE(2)',
  `sources_exists`                  int                           DEFAULT NULL COMMENT 'From [record]-`json->>"$.sourcesExists"`   or From [record]-`sources_exists`,   Values: NOT_PRESENT(0), PRESENT(1), NOT_AVAILABLE(2)',
  `javadoc_exists`                  int                           DEFAULT NULL COMMENT 'From [record]-`json->>"$.javadocExists"`   or From [record]-`javadoc_exists`,   Values: NOT_PRESENT(0), PRESENT(1), NOT_AVAILABLE(2)',

  `classifier`                  varchar( 128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'From [record]-`json->>"$.classifier"`',      -- 2023.02.12  Max     54
  `classifier_length`               int                           DEFAULT NULL COMMENT 'From [record]-`classifier_length`',
  `file_extension`              varchar( 254) COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'From [record]-`json->>"$.fileExtension"`',   -- 2023.02.12  Max    113
  `packaging`                   varchar( 254) COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'From [record]-`json->>"$.packaging"`',       -- 2023.02.12  Max    113
  `name`                        varchar(1024) COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'From [record]-`json->>"$.name"`',            -- 2023.02.12  Max    190
  `description`                 MEDIUMTEXT    COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'From [record]-`json->>"$.description"`',     -- 2023.02.12  Max 53,221

  KEY `index_gav` (`group_id`,`artifact_id`,`artifact_version`),
  KEY `index_fname` (`file_name`)
) ENGINE=InnoDB COLLATE=utf8mb4_bin COMMENT='Groups Artifact Version';


DROP TABLE IF exists `g`;
CREATE TABLE         `g` (
  `group_id`                    varchar(254)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'From [record]-`json->>"$.groupId"`',

  `artifact_version_counter`        int                           DEFAULT NULL,
  `major_version_counter`           int                           DEFAULT NULL,
  `version_seq_max`              bigint                           DEFAULT NULL,
  `last_modified_max`              DATE                           DEFAULT NULL,
  `group_id_left1`              varchar(128) COLLATE utf8mb4_bin GENERATED ALWAYS AS (substring_index(`group_id`,'.',1)) VIRTUAL,
  `group_id_left2`              varchar(254) COLLATE utf8mb4_bin GENERATED ALWAYS AS (substring_index(`group_id`,'.',2)) VIRTUAL,
  `group_id_left3`              varchar(254) COLLATE utf8mb4_bin GENERATED ALWAYS AS (substring_index(`group_id`,'.',3)) VIRTUAL,
  `group_id_left4`              varchar(254) COLLATE utf8mb4_bin GENERATED ALWAYS AS (substring_index(`group_id`,'.',4)) VIRTUAL,

  PRIMARY KEY (`group_id`),
  KEY `index_group_id_left1` (`group_id_left1`),
  KEY `index_group_id_left2` (`group_id_left2`),
  KEY `index_group_id_left3` (`group_id_left3`),
  KEY `index_group_id_left4` (`group_id_left4`)
) ENGINE=InnoDB COLLATE=utf8mb4_bin COMMENT='Groups';


DROP TABLE IF exists `ga`;
CREATE TABLE         `ga` (
  `group_id`                    varchar(254)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'From [record]-`json->>"$.groupId"`',
  `artifact_id`                 varchar(254)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'From [record]-`json->>"$.artifactId"`',

  `artifact_version_counter`        int                           DEFAULT NULL,
  `major_version_counter`           int                           DEFAULT NULL,
  `version_seq_max`              bigint                           DEFAULT NULL,
  `last_modified_max`              DATE                           DEFAULT NULL,

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
  file_name,
  major_version,
  version_seq,
  last_modified                                        AS mvn_last_modified,
  concat('mvn dependency:copy -U -DoutputDirectory=. -Dartifact=',
    if(isnull(classifier),
        concat(group_id, ':', artifact_id, ':', artifact_version,':', file_extension),
        concat(group_id, ':', artifact_id, ':', artifact_version,':', file_extension, ':', classifier)
    ))                                                 AS mvn_command,
  size,
  classifier,
  file_extension,
  packaging,
  name,
  description

FROM gav
;
