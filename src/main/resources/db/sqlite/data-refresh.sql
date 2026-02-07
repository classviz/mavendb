-- ============================================================
-- HIGH‑PERFORMANCE REFRESH SCRIPT FOR 88M‑ROW GAV TABLE
-- ============================================================

-- Create temporary index to speed up the aggregation queries
CREATE INDEX temp_idx_gav_group_artifact ON gav(group_id, artifact_id);


-- Refresh the aggregated data in tables 'ga' and 'g'

DROP TABLE IF EXISTS g_temp;
CREATE TEMP TABLE g_temp AS
SELECT
    group_id,
    count(DISTINCT artifact_version) AS artifact_version_counter,
    count(DISTINCT major_version)    AS major_version_counter,
    max(version_seq)                 AS version_seq_max,
    max(file_modified)               AS file_modified_max
FROM gav
GROUP BY group_id;
SELECT datetime('now', 'localtime');

DROP TABLE IF EXISTS ga_temp;
CREATE TEMP TABLE ga_temp AS
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
INSERT INTO g  SELECT * FROM g_temp;
SELECT datetime('now', 'localtime');

DELETE FROM ga;
INSERT INTO ga SELECT * FROM ga_temp;
SELECT datetime('now', 'localtime');


-- Create indexes on the aggregated tables and drop the temporary index

DROP   INDEX temp_idx_gav_group_artifact;
CREATE INDEX IF NOT EXISTS idx_gav               ON gav(group_id, artifact_id, artifact_version, version_seq, major_version);
CREATE INDEX IF NOT EXISTS idx_gav_file_name     ON gav(file_name);
CREATE INDEX IF NOT EXISTS idx_ga                ON ga (group_id, artifact_id);
CREATE INDEX IF NOT EXISTS idx_g                 ON g  (group_id);
SELECT datetime('now', 'localtime');
