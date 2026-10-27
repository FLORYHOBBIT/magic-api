-- magic-api-plugin-upload 建表脚本（SQLSERVER），表名前缀 magic_upload_
-- 由 SchemaSqlFilesTest 生成，请勿手工修改。默认开启自动建表（magic-api.upload.init-schema=true），
-- 只有数据库账号没有建表权限时才需要由 DBA 手工执行。修改了 table-prefix 时请同步替换表名前缀。

CREATE TABLE magic_upload_checkpoint (job_name NVARCHAR(128) NOT NULL, checkpoint_type NVARCHAR(16), checkpoint_value NVARCHAR(512), update_time DATETIME2, PRIMARY KEY (job_name))
GO

CREATE TABLE magic_upload_lock (job_name NVARCHAR(128) NOT NULL, owner NVARCHAR(128) NOT NULL, lock_time DATETIME2, expire_time DATETIME2 NOT NULL, PRIMARY KEY (job_name))
GO

CREATE TABLE magic_upload_record (id NVARCHAR(32) NOT NULL, job_name NVARCHAR(128) NOT NULL, status NVARCHAR(16), total_rows BIGINT, success_rows BIGINT, failed_rows BIGINT, total_batches INT, failed_batches INT, start_checkpoint NVARCHAR(512), end_checkpoint NVARCHAR(512), message NVARCHAR(1000), start_time DATETIME2, end_time DATETIME2, cost_ms BIGINT, PRIMARY KEY (id))
GO
CREATE INDEX magic_upload_record_ix ON magic_upload_record (job_name, start_time)
GO

CREATE TABLE magic_upload_batch_log (id NVARCHAR(32) NOT NULL, record_id NVARCHAR(32), job_name NVARCHAR(128) NOT NULL, batch_index INT, row_count INT, success_flag INT, attempts INT, start_checkpoint NVARCHAR(512), end_checkpoint NVARCHAR(512), message NVARCHAR(1000), response NVARCHAR(MAX), cost_ms BIGINT, create_time DATETIME2, PRIMARY KEY (id))
GO
CREATE INDEX magic_upload_batch_ix ON magic_upload_batch_log (record_id)
GO

CREATE TABLE magic_upload_failed_batch (id NVARCHAR(32) NOT NULL, record_id NVARCHAR(32), job_name NVARCHAR(128) NOT NULL, batch_index INT, row_count INT, start_checkpoint NVARCHAR(512), end_checkpoint NVARCHAR(512), payload NVARCHAR(MAX), message NVARCHAR(1000), status NVARCHAR(16), retry_count INT, create_time DATETIME2, update_time DATETIME2, PRIMARY KEY (id))
GO
CREATE INDEX magic_upload_failed_ix ON magic_upload_failed_batch (job_name, status)
GO

