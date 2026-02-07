-- ============================================================
-- SCHEMA
-- ============================================================

CREATE SCHEMA IF NOT EXISTS mavendb;
SET search_path TO mavendb;


-- ============================================================
-- TABLE: gav
--
-- Use UNLOGGED tables for huge speed boost on write operations
-- at the cost of durability in case of a crash.
-- ============================================================

DROP TABLE IF EXISTS gav CASCADE;

CREATE UNLOGGED TABLE gav (
    seqid               bigint NOT NULL,

    major_version       int,
    version_seq         bigint NOT NULL,

    record_modified     bigint,
    file_modified       bigint,
    file_size           bigint,

    has_signature       boolean,
    has_sources         boolean,
    has_javadoc         boolean,

    sha1                char(40),

    group_id            varchar(256) NOT NULL,
    artifact_id         varchar(256) NOT NULL,
    artifact_version    varchar(128) NOT NULL,

    classifier          varchar(128),
    packaging           varchar(256),
    file_extension      varchar(256),
    file_name           varchar(256),

    name                varchar(1024),

    bundle_description  varchar(4096),
    bundle_docurl       varchar(512),
    bundle_license      varchar(1024),
    bundle_name         varchar(512),
    bundle_symbolicname varchar(512),
    bundle_version      varchar(256),

    require_bundle      varchar(8196),
    export_service      varchar(8196),

    export_package      text,
    import_package      text,

    description         text,

    json                jsonb
);


-- ============================================================
-- TABLE: g
-- ============================================================

DROP TABLE IF EXISTS g;

CREATE UNLOGGED TABLE g (
    group_id                    varchar(256) PRIMARY KEY,

    artifact_version_counter    int,
    major_version_counter       int,
    version_seq_max             bigint,
    file_modified_max           bigint,

    group_id_left1 varchar(128) GENERATED ALWAYS AS (split_part(group_id, '.', 1)) STORED,
    group_id_left2 varchar(256) GENERATED ALWAYS AS (split_part(group_id, '.', 2)) STORED,
    group_id_left3 varchar(256) GENERATED ALWAYS AS (split_part(group_id, '.', 3)) STORED,
    group_id_left4 varchar(256) GENERATED ALWAYS AS (split_part(group_id, '.', 4)) STORED
);

-- ============================================================
-- TABLE: ga
-- ============================================================

DROP TABLE IF EXISTS ga;

CREATE UNLOGGED TABLE ga (
    group_id                varchar(256) NOT NULL,
    artifact_id             varchar(256) NOT NULL,

    artifact_version_counter    int,
    major_version_counter       int,
    version_seq_max             bigint,
    file_modified_max           bigint,

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

    concat(
        'mvn dependency:copy -U -DoutputDirectory=. -Dartifact=',
        CASE
            WHEN classifier IS NULL THEN
                concat(group_id, ':', artifact_id, ':', artifact_version, ':', file_extension)
            ELSE
                concat(group_id, ':', artifact_id, ':', artifact_version, ':', file_extension, ':', classifier)
        END
    ) AS mvn_command,

    name
FROM gav;
