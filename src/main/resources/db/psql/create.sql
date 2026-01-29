-- ============================================================
-- SCHEMA
-- ============================================================

CREATE SCHEMA IF NOT EXISTS mavendb;
SET search_path TO mavendb;


-- ============================================================
-- TABLE: gav
-- ============================================================

DROP TABLE IF EXISTS gav CASCADE;

CREATE TABLE gav (
    seqid               bigint      NOT NULL,

    major_version       int,
    version_seq         bigint NOT NULL,

    record_modified     bigint,
    file_modified       bigint,
    file_size           bigint,

    has_signature       boolean,
    has_sources         boolean,
    has_javadoc         boolean,

    sha1                char(40),

    group_id            varchar(254) NOT NULL,
    artifact_id         varchar(254) NOT NULL,
    artifact_version    varchar(128) NOT NULL,

    classifier          varchar(128),
    packaging           varchar(254),
    file_extension      varchar(254),

    -- PSQL generated column (stored)
    file_name varchar(512) GENERATED ALWAYS AS (
        left(
            CASE
                WHEN classifier IS NULL THEN
                    artifact_id || '-' || artifact_version || '.' || file_extension
                ELSE
                    artifact_id || '-' || artifact_version || '-' || classifier || '.' || file_extension
            END,
            512
        )
    ) STORED,

    name                varchar(1024),
    description         text,

    json                jsonb
);


-- ============================================================
-- TABLE: g
-- ============================================================

DROP TABLE IF EXISTS g;

CREATE TABLE g (
    group_id                    varchar(254) PRIMARY KEY,

    artifact_version_counter    int,
    major_version_counter       int,
    version_seq_max             bigint,
    last_modified_max           timestamp,

    group_id_left1 varchar(128) GENERATED ALWAYS AS (split_part(group_id, '.', 1)) STORED,
    group_id_left2 varchar(254) GENERATED ALWAYS AS (split_part(group_id, '.', 2)) STORED,
    group_id_left3 varchar(254) GENERATED ALWAYS AS (split_part(group_id, '.', 3)) STORED,
    group_id_left4 varchar(254) GENERATED ALWAYS AS (split_part(group_id, '.', 4)) STORED
);

-- ============================================================
-- TABLE: ga
-- ============================================================

DROP TABLE IF EXISTS ga;

CREATE TABLE ga (
    group_id                varchar(254) NOT NULL,
    artifact_id             varchar(254) NOT NULL,

    artifact_version_counter    int,
    major_version_counter       int,
    version_seq_max             bigint,
    last_modified_max           timestamp,

    PRIMARY KEY (group_id, artifact_id)
);

-- ============================================================
-- VIEW: v_gav
-- ============================================================

DROP VIEW IF EXISTS v_gav;

CREATE VIEW v_gav AS
SELECT
    group_id,
    artifact_id,
    artifact_version,
    file_name,
    major_version,
    version_seq,
    file_modified AS mvn_file_modified,

    concat(
        'mvn dependency:copy -U -DoutputDirectory=. -Dartifact=',
        CASE
            WHEN classifier IS NULL THEN
                concat(group_id, ':', artifact_id, ':', artifact_version, ':', file_extension)
            ELSE
                concat(group_id, ':', artifact_id, ':', artifact_version, ':', file_extension, ':', classifier)
        END
    ) AS mvn_command,

    file_size,
    classifier,
    file_extension,
    packaging,
    name,
    description
FROM gav;
