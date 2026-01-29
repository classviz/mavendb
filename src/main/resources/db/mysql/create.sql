CREATE SCHEMA IF NOT EXISTS `mavendb` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin ;
USE `mavendb`;


DROP TABLE IF exists `gav`;
CREATE TABLE         `gav` (
  `seqid`                       bigint                                NOT NULL COMMENT 'Squence ID',

  `major_version`                   int                           DEFAULT NULL COMMENT 'VersionAnalyser.getMajorVersion()',
  `version_seq`                  bigint                               NOT NULL COMMENT 'VersionAnalyser.getVersionSeq()',

  `record_modified`              bigint                           DEFAULT NULL COMMENT 'Record.recordModified',
  `file_modified`                bigint                           DEFAULT NULL COMMENT 'Record.fileModified',
  `file_size`                    bigint                           DEFAULT NULL COMMENT 'Record.fileSize',

  `has_signature`               BOOLEAN                           DEFAULT NULL COMMENT 'Record.hasSignature',
  `has_sources`                 BOOLEAN                           DEFAULT NULL COMMENT 'Record.hasSources',
  `has_javadoc`                 BOOLEAN                           DEFAULT NULL COMMENT 'Record.hasJavadoc',

  `sha1`                           char( 40)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Record.sha1',

  `group_id`                    varchar(254)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'Record.groupId',         -- 2023.02.12  Max 93
  `artifact_id`                 varchar(254)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'Record.artifactId',      -- 2023.02.12  Max 87
  `artifact_version`            varchar(128)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'Record.version',         -- 2023.02.12  Max 94

  `classifier`                  varchar(128)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Record.classifier',      -- 2023.02.12  Max     54
  `packaging`                   varchar(254)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Record.packaging',       -- 2023.02.12  Max    113
  `file_extension`              varchar(254)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Record.fileExtension',   -- 2023.02.12  Max    113
  `file_name`                   varchar(512)  COLLATE utf8mb4_bin  GENERATED ALWAYS AS (LEFT( if( isnull(classifier),
                                                                      concat(artifact_id, '-', artifact_version, '.', file_extension),
                                                                      concat(artifact_id, '-', artifact_version, '-', classifier, '.', file_extension)), 512)) virtual
                                                                      NOT NULL COMMENT 'MVN file name',

  `name`                        varchar(1024) COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Record.name',            -- 2023.02.12  Max    190
  `description`                 MEDIUMTEXT    COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Record.description',     -- 2023.02.12  Max 53,221

  `json`                           json                           DEFAULT NULL COMMENT 'Other fields other than above',

  PRIMARY KEY (`seqid`)
) ENGINE=InnoDB COLLATE=utf8mb4_bin COMMENT='Groups Artifact Version';


DROP TABLE IF exists `g`;
CREATE TABLE         `g` (
  `group_id`                    varchar(254)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'From gav.group_id',

  `artifact_version_counter`        int                           DEFAULT NULL,
  `major_version_counter`           int                           DEFAULT NULL,
  `version_seq_max`              bigint                           DEFAULT NULL,
  `file_modified_max`            bigint                           DEFAULT NULL,
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
  `group_id`                    varchar(254)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'From gav.group_id',
  `artifact_id`                 varchar(254)  COLLATE utf8mb4_bin     NOT NULL COMMENT 'From gav.artifact_id',

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
  file_name,
  major_version,
  version_seq,
  file_modified                                        AS mvn_file_modified,
  concat('mvn dependency:copy -U -DoutputDirectory=. -Dartifact=',
    if(isnull(classifier),
        concat(group_id, ':', artifact_id, ':', artifact_version,':', file_extension),
        concat(group_id, ':', artifact_id, ':', artifact_version,':', file_extension, ':', classifier)
    ))                                                 AS mvn_command,

  file_size,
  classifier,
  file_extension,
  packaging,
  name,
  description

FROM gav
;
