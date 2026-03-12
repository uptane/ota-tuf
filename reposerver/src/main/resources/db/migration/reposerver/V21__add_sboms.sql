CREATE TABLE sboms (
  filename   VARCHAR(254) NOT NULL,
  uri        VARCHAR(2048) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (filename)
);

ALTER view aggregated_items AS
      select items.*, IF(sboms.uri is null, false, true) AS has_sbom FROM
      (SELECT 'targets.json' origin,
          JSON_UNQUOTE(JSON_EXTRACT(custom, '$.name')) name,
          JSON_UNQUOTE(JSON_EXTRACT(custom, '$.version')) version,
          filename,
          checksum,
          length,
          uri,
          IFNULL(JSON_EXTRACT(custom, '$.hardwareIds'),'[]') hardwareids,
          created_at,
          repo_id,
          custom
      from target_items
      UNION
      select JSON_UNQUOTE(rolename) origin,
          JSON_UNQUOTE(JSON_EXTRACT(custom, '$.name')) name,
          JSON_UNQUOTE(JSON_EXTRACT(custom, '$.version')) version,
          filename,
          checksum,
          length,
          JSON_UNQUOTE(JSON_EXTRACT(custom, '$.uri')) uri,
          IFNULL(JSON_EXTRACT(custom, '$.hardwareIds'),'[]') hardwareids,
          COALESCE(target_created_at, JSON_EXTRACT(custom, '$.created_at'), created_at) created_at,
          repo_id,
          custom
      FROM delegated_items) AS items LEFT JOIN sboms USING (filename)
;
