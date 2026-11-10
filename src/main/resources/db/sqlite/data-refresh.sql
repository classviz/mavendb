-- ============================================================
-- HIGH-PERFORMANCE REFRESH SCRIPT FOR THE GAV TABLE
-- ============================================================

-- Build the lookup index first, and make it COVERING for the aggregations below
-- (it holds every column they read). The GROUP BY queries are then answered from the
-- small, already-sorted index alone, without reading the wide gav rows
-- (json, export_package, ...), and without a separate temporary index.
CREATE INDEX IF NOT EXISTS idx_gav ON gav(group_id, artifact_id, artifact_version, version_seq, major_version, file_modified);
SELECT datetime('now', 'localtime');


-- Refresh the aggregated data in tables 'ga' and 'g'

DELETE FROM ga;
INSERT INTO ga
SELECT
    group_id,
    artifact_id,
    count(DISTINCT artifact_version) AS artifact_version_counter,
    count(DISTINCT major_version)    AS major_version_counter,
    max(version_seq)                 AS version_seq_max,
    max(file_modified)               AS file_modified_max
FROM gav
GROUP BY group_id, artifact_id;
SELECT datetime('now', 'localtime');

DELETE FROM g;
INSERT INTO g
SELECT
    group_id,
    count(DISTINCT artifact_version) AS artifact_version_counter,
    count(DISTINCT major_version)    AS major_version_counter,
    max(version_seq)                 AS version_seq_max,
    max(file_modified)               AS file_modified_max
FROM gav
GROUP BY group_id;
SELECT datetime('now', 'localtime');


-- Remaining indexes

CREATE INDEX IF NOT EXISTS idx_gav_file_name     ON gav(file_name);
CREATE INDEX IF NOT EXISTS idx_ga                ON ga (group_id, artifact_id);
CREATE INDEX IF NOT EXISTS idx_g                 ON g  (group_id);
SELECT datetime('now', 'localtime');
