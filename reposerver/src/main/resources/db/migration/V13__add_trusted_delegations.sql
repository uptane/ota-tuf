CREATE TABLE `trusted_delegations` (
  `repo_id` char(36) NOT NULL,
  `name`  varchar(50) NOT NULL,
  `key_ids` longtext NOT NULL,
  `paths` longtext NOT NULL,
  `threshold` longtext NOT NULL,
  `terminating` boolean NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  PRIMARY KEY (`repo_id`,`name`)
)
;

CREATE TABLE `trusted_delegation_keys` (
  `repo_id` char(36) NOT NULL,
  `key_id`  varchar(80) NOT NULL,
  `key_value` longtext NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  PRIMARY KEY (`repo_id`,`key_id`)
)
