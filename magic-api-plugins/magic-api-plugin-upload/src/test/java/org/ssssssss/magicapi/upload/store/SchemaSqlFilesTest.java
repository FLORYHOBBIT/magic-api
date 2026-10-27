package org.ssssssss.magicapi.upload.store;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * db/ 下的手工建表脚本必须与自动建表语句一致。
 * 修改 UploadSchema 后执行 mvnw test -Dtest=SchemaSqlFilesTest -Dupload.writeDdl=true 重新生成。
 */
class SchemaSqlFilesTest {

	@Test
	void sqlFilesMatchSchema() throws Exception {
		check(DatabaseType.ORACLE, "db/oracle.sql");
		check(DatabaseType.SQLSERVER, "db/sqlserver.sql");
	}

	private static void check(DatabaseType type, String file) throws Exception {
		String expected = render(type);
		Path path = Paths.get(file);
		if (Boolean.getBoolean("upload.writeDdl")) {
			Files.createDirectories(path.getParent());
			Files.write(path, expected.getBytes(StandardCharsets.UTF_8));
		}
		assertThat(path).as(file + " 不存在，请用 -Dupload.writeDdl=true 生成").exists();
		String actual = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
		assertThat(actual).as(file + " 与 UploadSchema 不一致，请用 -Dupload.writeDdl=true 重新生成").isEqualTo(expected);
	}

	static String render(DatabaseType type) {
		UploadSchema schema = new UploadSchema("magic_upload_");
		StringBuilder sb = new StringBuilder();
		sb.append("-- magic-api-plugin-upload 建表脚本（").append(type).append("），表名前缀 magic_upload_\n");
		sb.append("-- 由 SchemaSqlFilesTest 生成，请勿手工修改。默认开启自动建表（magic-api.upload.init-schema=true），\n");
		sb.append("-- 只有数据库账号没有建表权限时才需要由 DBA 手工执行。修改了 table-prefix 时请同步替换表名前缀。\n\n");
		String separator = type == DatabaseType.SQLSERVER ? "\nGO\n" : ";\n";
		for (String table : schema.tables()) {
			for (String sql : schema.ddl(table, type)) {
				sb.append(sql).append(separator);
			}
			sb.append('\n');
		}
		return sb.toString();
	}
}
