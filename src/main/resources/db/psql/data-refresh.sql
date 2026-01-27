-- ============================================================
-- Correct data records
-- ============================================================

SET search_path TO mavendb;

SELECT now() || ' Started' AS status;

-- ============================================================
-- Refresh table: gav
-- ============================================================

TRUNCATE TABLE gav;

INSERT INTO gav (
    seqid,
    group_id,
    artifact_id,
    artifact_version,
    major_version,
    version_seq,
    last_modified,
    size,
    sha1,
    signature_exists,
    sources_exists,
    javadoc_exists,
    classifier,
    file_extension,
    packaging,
    name,
    description
)
SELECT
    seqid,

    json ->> 'groupId'       AS group_id,
    json ->> 'artifactId'    AS artifact_id,
    json ->> 'version'       AS artifact_version,

    major_version,
    version_seq,

    to_timestamp( (json ->> 'recordModified')::bigint / 1000 ) AS last_modified,
    (json ->> 'fileSize')::bigint                              AS size,
    substr(json ->> 'sha1', 1, 40)                             AS sha1,

    CASE json ->> 'hasSignature'
        WHEN 'true'  THEN TRUE
        WHEN 'false' THEN FALSE
        ELSE NULL
    END AS signature_exists,

    CASE json ->> 'hasSources'
        WHEN 'true'  THEN TRUE
        WHEN 'false' THEN FALSE
        ELSE NULL
    END AS sources_exists,

    CASE json ->> 'hasJavadoc'
        WHEN 'true'  THEN TRUE
        WHEN 'false' THEN FALSE
        ELSE NULL
    END AS javadoc_exists,

    json ->> 'classifier'     AS classifier,
    json ->> 'fileExtension'  AS file_extension,
    json ->> 'packaging'      AS packaging,
    json ->> 'name'           AS name,
    json ->> 'description'    AS description
FROM record;

SELECT now() || ' Table gav refresh data finished' AS status;

-- ============================================================
-- Fix Tomcat zip.sha512 → zip
-- ============================================================

UPDATE gav
SET file_extension = 'zip'
WHERE group_id = 'org.apache.tomcat'
  AND artifact_id = 'tomcat'
  AND file_extension = 'zip.sha512';

SELECT now() || ' Table gav Fix org.apache.tomcat/tomcat/zip.sha512 finished' AS status;

-- ============================================================
-- Refresh table: g
-- ============================================================

TRUNCATE TABLE g;

INSERT INTO g (
    group_id,
    artifact_version_counter,
    major_version_counter,
    version_seq_max,
    last_modified_max
)
SELECT
    group_id,
    count(DISTINCT artifact_version) AS artifact_version_counter,
    count(DISTINCT major_version)    AS major_version_counter,
    max(version_seq)                 AS version_seq_max,
    max(last_modified)               AS last_modified_max
FROM gav
GROUP BY group_id
ORDER BY group_id;

SELECT now() || ' Table g refresh data finished' AS status;

-- ============================================================
-- Refresh table: ga
-- ============================================================

TRUNCATE TABLE ga;

INSERT INTO ga (
    group_id,
    artifact_id,
    artifact_version_counter,
    major_version_counter,
    version_seq_max,
    last_modified_max
)
SELECT
    group_id,
    artifact_id,
    count(DISTINCT artifact_version) AS artifact_version_counter,
    count(DISTINCT major_version)    AS major_version_counter,
    max(version_seq)                 AS version_seq_max,
    max(last_modified)               AS last_modified_max
FROM gav
GROUP BY group_id, artifact_id
ORDER BY group_id, artifact_id;

SELECT now() || ' Table ga refresh data finished' AS status;
