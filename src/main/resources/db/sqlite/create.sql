
CREATE TABLE "g"(
  "group_id"                  TEXT,
  "artifact_version_counter"  INTEGER,
  "major_version_counter"     INTEGER,
  "version_seq_max"           TEXT,
  "file_modified_max"         TEXT
);

CREATE TABLE "ga"(
  "group_id"                  TEXT,
  "artifact_id"               TEXT,
  "artifact_version_counter"  INTEGER,
  "major_version_counter"     INTEGER,
  "version_seq_max"           TEXT,
  "file_modified_max"         TEXT
);

CREATE TABLE "gav"(
  "group_id"                  TEXT,
  "artifact_id"               TEXT,
  "artifact_version"          TEXT,

  "file_name"                 TEXT,

  "major_version"             INTEGER,
  "version_seq"               TEXT,

  "file_modified"             TEXT,
  "file_size"                 TEXT,
  "sha1"                      TEXT,

  "has_signature"          INTEGER,
  "has_sources"            INTEGER,
  "has_javadoc"            INTEGER,

  "classifier"                TEXT,
  "file_extension"            TEXT,
  "packaging"                 TEXT,
  "name"                      TEXT
);
