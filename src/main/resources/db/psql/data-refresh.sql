-- ============================================================
-- Correct data records
-- ============================================================

SET search_path TO mavendb;

SELECT now() || ' Started' AS status;

CREATE INDEX index_gav
    ON gav (group_id, artifact_id, artifact_version);

CREATE INDEX index_fname
    ON gav (file_name);

SELECT now() || ' Table gav create index finished' AS status;

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

CREATE INDEX index_group_id_left1 ON g (group_id_left1);
CREATE INDEX index_group_id_left2 ON g (group_id_left2);
CREATE INDEX index_group_id_left3 ON g (group_id_left3);
CREATE INDEX index_group_id_left4 ON g (group_id_left4);

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
