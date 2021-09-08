
alter table `keys` MODIFY COLUMN `role_type` ENUM('ROOT', 'SNAPSHOT', 'TARGETS', 'TIMESTAMP', 'OFFLINE_TARGETS') NOT NULL
;

alter table `key_gen_requests` MODIFY COLUMN `role_type` ENUM('ROOT', 'SNAPSHOT', 'TARGETS', 'TIMESTAMP', 'OFFLINE_TARGETS') NOT NULL
;