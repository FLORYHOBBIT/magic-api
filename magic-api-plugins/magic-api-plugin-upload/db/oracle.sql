-- magic-api-plugin-upload 建表脚本（ORACLE），表名前缀 magic_upload_
-- 由 SchemaSqlFilesTest 生成，请勿手工修改。默认开启自动建表（magic-api.upload.init-schema=true），
-- 只有数据库账号没有建表权限时才需要由 DBA 手工执行。修改了 table-prefix 时请同步替换表名前缀。

CREATE TABLE magic_upload_checkpoint (job_name VARCHAR2(128 CHAR) NOT NULL, checkpoint_type VARCHAR2(16 CHAR), checkpoint_value VARCHAR2(512 CHAR), update_time TIMESTAMP, PRIMARY KEY (job_name));

CREATE TABLE magic_upload_lock (job_name VARCHAR2(128 CHAR) NOT NULL, owner VARCHAR2(128 CHAR) NOT NULL, lock_time TIMESTAMP, expire_time TIMESTAMP NOT NULL, PRIMARY KEY (job_name));

CREATE TABLE magic_upload_record (id VARCHAR2(32 CHAR) NOT NULL, job_name VARCHAR2(128 CHAR) NOT NULL, status VARCHAR2(16 CHAR), total_rows NUMBER(19), success_rows NUMBER(19), failed_rows NUMBER(19), total_batches NUMBER(10), failed_batches NUMBER(10), start_checkpoint VARCHAR2(512 CHAR), end_checkpoint VARCHAR2(512 CHAR), message VARCHAR2(1000 CHAR), start_time TIMESTAMP, end_time TIMESTAMP, cost_ms NUMBER(19), PRIMARY KEY (id));
CREATE INDEX magic_upload_record_ix ON magic_upload_record (job_name, start_time);

CREATE TABLE magic_upload_batch_log (id VARCHAR2(32 CHAR) NOT NULL, record_id VARCHAR2(32 CHAR), job_name VARCHAR2(128 CHAR) NOT NULL, batch_index NUMBER(10), row_count NUMBER(10), success_flag NUMBER(10), attempts NUMBER(10), start_checkpoint VARCHAR2(512 CHAR), end_checkpoint VARCHAR2(512 CHAR), message VARCHAR2(1000 CHAR), response CLOB, cost_ms NUMBER(19), create_time TIMESTAMP, PRIMARY KEY (id));
CREATE INDEX magic_upload_batch_ix ON magic_upload_batch_log (record_id);

CREATE TABLE magic_upload_failed_batch (id VARCHAR2(32 CHAR) NOT NULL, record_id VARCHAR2(32 CHAR), job_name VARCHAR2(128 CHAR) NOT NULL, batch_index NUMBER(10), row_count NUMBER(10), start_checkpoint VARCHAR2(512 CHAR), end_checkpoint VARCHAR2(512 CHAR), payload CLOB, message VARCHAR2(1000 CHAR), status VARCHAR2(16 CHAR), retry_count NUMBER(10), create_time TIMESTAMP, update_time TIMESTAMP, PRIMARY KEY (id));
CREATE INDEX magic_upload_failed_ix ON magic_upload_failed_batch (job_name, status);

