-- SQLite database schema for mavendb
-- Optimized for 80-200 million records with proper indexes and data types

CREATE TABLE g(
  group_id                  TEXT,
  artifact_version_counter  INTEGER,
  major_version_counter     INTEGER,
  version_seq_max           TEXT,
  file_modified_max         TEXT
);

CREATE TABLE ga(
  group_id                  TEXT,
  artifact_id               TEXT,
  artifact_version_counter  INTEGER,
  major_version_counter     INTEGER,
  version_seq_max           TEXT,
  file_modified_max         TEXT
);

DROP TABLE IF EXISTS gav;
CREATE TABLE gav (
  seqid                     INTEGER PRIMARY KEY NOT NULL,

  major_version             INTEGER,
  version_seq               INTEGER NOT NULL,

  record_modified           INTEGER,
  file_modified             INTEGER,
  file_size                 INTEGER,

  has_signature             INTEGER,
  has_sources               INTEGER,
  has_javadoc               INTEGER,

  sha1                      TEXT,

  group_id                  TEXT NOT NULL,
  artifact_id               TEXT NOT NULL,
  artifact_version          TEXT NOT NULL,

  classifier                TEXT,
  packaging                 TEXT,
  file_extension            TEXT,

  name                      TEXT,
  description               TEXT,

  json                      TEXT
);
