-- ============================================================
-- REFRESH SCRIPT FOR THE GAV TABLE (115M+ rows)
-- ============================================================
--
-- idx_gav is built first and covers every column the aggregations read,
-- so the GROUP BY queries scan the index in order instead of the wide gav rows (which hold the json).
-- This replaced a temporary (group_id, artifact_id) index that was built, used and dropped:
-- one fewer index build over all rows. Nov 2026: old script 562 s, new script estimated about 390 s.

-- Multi-threaded sort for CREATE INDEX, and a 2 GB page cache for the sorts, this connection only
PRAGMA threads = 8;
PRAGMA cache_size = -2000000;

CREATE INDEX IF NOT EXISTS idx_gav ON gav(group_id, artifact_id, artifact_version, version_seq, major_version, file_modified);
SELECT datetime('now', 'localtime');


-- Refresh the aggregated data in tables 'g' and 'ga'

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


-- Remaining indexes

CREATE INDEX IF NOT EXISTS idx_gav_file_name     ON gav(file_name);
CREATE INDEX IF NOT EXISTS idx_ga                ON ga (group_id, artifact_id);
CREATE INDEX IF NOT EXISTS idx_g                 ON g  (group_id);
SELECT datetime('now', 'localtime');
