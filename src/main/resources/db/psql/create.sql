-- ============================================================
-- SCHEMA
-- ============================================================

CREATE SCHEMA IF NOT EXISTS mavendb;
SET search_path TO mavendb;

-- ============================================================
-- TABLE: record
-- ============================================================

DROP TABLE IF EXISTS record;

CREATE TABLE record (
    seqid           bigint      PRIMARY KEY,
    major_version   int,
    version_seq     bigint      NOT NULL DEFAULT 0,
    json            jsonb
);

-- ============================================================
-- TABLE: gav
-- ============================================================

DROP TABLE IF EXISTS gav CASCADE;

CREATE TABLE gav (
    seqid               bigint      NOT NULL,

    group_id            varchar(254) NOT NULL,
    artifact_id         varchar(254) NOT NULL,
    artifact_version    varchar(128) NOT NULL,

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

    major_version       int,
    version_seq         bigint NOT NULL,

    last_modified       timestamp,
    size                bigint,
    sha1                char(40),

    signature_exists    boolean,
    sources_exists      boolean,
    javadoc_exists      boolean,

    classifier          varchar(128),
    file_extension      varchar(254),
    packaging           varchar(254),
    name                varchar(1024),
    description         text,

    -- Indexes
    CONSTRAINT gav_pk PRIMARY KEY (seqid)
);

CREATE INDEX index_gav
    ON gav (group_id, artifact_id, artifact_version);

CREATE INDEX index_fname
    ON gav (file_name);

-- ============================================================
-- TABLE: g
-- ============================================================

DROP TABLE IF EXISTS g;

CREATE TABLE g (
    group_id                varchar(254) PRIMARY KEY,

    artifact_version_counter    int,
    major_version_counter       int,
    version_seq_max             bigint,
    last_modified_max           timestamp,

    group_id_left1 varchar(128) GENERATED ALWAYS AS (split_part(group_id, '.', 1)) STORED,
    group_id_left2 varchar(254) GENERATED ALWAYS AS (split_part(group_id, '.', 2)) STORED,
    group_id_left3 varchar(254) GENERATED ALWAYS AS (split_part(group_id, '.', 3)) STORED,
    group_id_left4 varchar(254) GENERATED ALWAYS AS (split_part(group_id, '.', 4)) STORED
);

CREATE INDEX index_group_id_left1 ON g (group_id_left1);
CREATE INDEX index_group_id_left2 ON g (group_id_left2);
CREATE INDEX index_group_id_left3 ON g (group_id_left3);
CREATE INDEX index_group_id_left4 ON g (group_id_left4);

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
    last_modified AS mvn_last_modified,

    concat(
        'mvn dependency:copy -U -DoutputDirectory=. -Dartifact=',
        CASE
            WHEN classifier IS NULL THEN
                concat(group_id, ':', artifact_id, ':', artifact_version, ':', file_extension)
            ELSE
                concat(group_id, ':', artifact_id, ':', artifact_version, ':', file_extension, ':', classifier)
        END
    ) AS mvn_command,

    size,
    classifier,
    file_extension,
    packaging,
    name,
    description
FROM gav;
