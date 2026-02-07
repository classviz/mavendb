-- Query Samples

SELECT
  max(length(group_id))                           AS group_id_max,
  max(length(artifact_id))                        AS artifact_id_max,
  max(length(artifact_version))                   AS artifact_version_max,
  max(length(classifier))                         AS classifier_max,
  max(length(packaging))                          AS packaging_max,
  max(length(file_extension))                     AS file_extension_max,
  max(length(file_name))                          AS file_name_max,
  max(length(name))                               AS name_max,
  max(length(description))                        AS description_max,
  max(length(json))                               AS json_max,
  max(length(json->>"$.\"Bundle-Description\""))  AS bundle_description_max,
  max(length(json->>"$.\"Bundle-DocURL\""))       AS bundle_docurl_max,
  max(length(json->>"$.\"Bundle-License\""))      AS bundle_license_max,
  max(length(json->>"$.\"Bundle-Name\""))         AS bundle_name_max,
  max(length(json->>"$.\"Bundle-SymbolicName\"")) AS bundle_symbolicname_max,
  max(length(json->>"$.\"Bundle-Version\""))      AS bundle_version_max,
  max(length(json->>"$.\"Export-Package\""))      AS export_package_max,
  max(length(json->>"$.\"Import-Package\""))      AS import_package_max
FROM gav
WHERE json IS NOT NULL
;


