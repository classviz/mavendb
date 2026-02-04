
-- Create indexes for high performance queries on large datasets
-- Composite index for common GAV queries
CREATE INDEX IF NOT EXISTS idx_gav ON gav(group_id, artifact_id, artifact_version, version_seq, major_version);
CREATE INDEX IF NOT EXISTS idx_ga  ON ga (group_id, artifact_id);
CREATE INDEX IF NOT EXISTS idx_g   ON g  (group_id);

-- Individual indexes for filtering and sorting
CREATE INDEX IF NOT EXISTS idx_gav_file_modified ON gav(file_modified);
