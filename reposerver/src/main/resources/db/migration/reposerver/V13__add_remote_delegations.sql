ALTER TABLE delegations
  ADD COLUMN `uri` VARCHAR(2048) NULL,
  ADD COLUMN `last_fetched_at` datetime(3) NULL
;
