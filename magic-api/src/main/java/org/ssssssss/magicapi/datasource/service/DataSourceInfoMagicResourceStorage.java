package org.ssssssss.magicapi.datasource.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StreamUtils;
import org.ssssssss.magicapi.datasource.model.DataSourceInfo;
import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.core.service.MagicResourceStorage;
import org.ssssssss.magicapi.utils.IoUtils;
import org.ssssssss.magicapi.utils.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class DataSourceInfoMagicResourceStorage implements MagicResourceStorage<DataSourceInfo>, JsonCodeConstants {

	public static final String FOLDER = "datasource";

	private static final String SUFFIX = ".json";

	private static final ObjectMapper EXPORT_MAPPER = new ObjectMapper();

	private MagicResourceService magicResourceService;

	@Override
	public String folder() {
		return FOLDER;
	}

	@Override
	public String suffix() {
		return SUFFIX;
	}

	@Override
	public Class<DataSourceInfo> magicClass() {
		return DataSourceInfo.class;
	}

	@Override
	public boolean requirePath() {
		return false;
	}

	@Override
	public boolean requiredScript() {
		return false;
	}

	@Override
	public boolean allowRoot() {
		return true;
	}

	@Override
	public String buildMappingKey(DataSourceInfo info) {
		return String.format("%s-%s", info.getKey(), info.getUpdateTime());
	}

	@Override
	public void validate(DataSourceInfo entity) {
		notBlank(entity.getUrl(), DS_URL_REQUIRED);
		notBlank(entity.getKey(), DS_KEY_REQUIRED);
		isTrue(IoUtils.validateFileName(entity.getKey()), DATASOURCE_KEY_INVALID);
		boolean noneMatchKey = magicResourceService.listFiles("datasource:0").stream()
				.map(it -> (DataSourceInfo)it)
				.filter(it -> !it.getId().equals(entity.getId()))
				.noneMatch(it -> Objects.equals(it.getKey(), entity.getKey()));
		isTrue(noneMatchKey, DS_KEY_CONFLICT);
	}

	@Override
	public void setMagicResourceService(MagicResourceService magicResourceService) {
		this.magicResourceService = magicResourceService;
	}

	@Override
	public DataSourceInfo read(byte[] bytes) {
		return JsonUtils.readValue(bytes, DataSourceInfo.class);
	}

	@Override
	public byte[] write(MagicEntity entity) {
		return JsonUtils.toJsonBytes(entity);
	}

	public static void stripExportPasswords(InputStream archive, OutputStream output, boolean dataSourceGroup) throws IOException {
		try (ZipInputStream input = new ZipInputStream(archive); ZipOutputStream sanitized = new ZipOutputStream(output)) {
			ZipEntry entry;
			while ((entry = input.getNextEntry()) != null) {
				String name = entry.getName();
				sanitized.putNextEntry(new ZipEntry(name));
				String path = name.replace('\\', '/');
				boolean definitionEntry = dataSourceGroup || path.regionMatches(true, 0, FOLDER + "/", 0, FOLDER.length() + 1);
				if (!entry.isDirectory() && path.endsWith(SUFFIX) && definitionEntry) {
					JsonNode definition = readExportDefinition(input);
					if (definition instanceof ObjectNode) {
						((ObjectNode) definition).remove("password");
						sanitized.write(EXPORT_MAPPER.writeValueAsBytes(definition));
					}
				} else {
					StreamUtils.copy(input, sanitized);
				}
				sanitized.closeEntry();
			}
		}
	}

	private static JsonNode readExportDefinition(InputStream input) throws IOException {
		try {
			return EXPORT_MAPPER.readTree(StreamUtils.copyToByteArray(input));
		} catch (IOException ignored) {
			throw new IOException("无法读取导出包中的数据源配置。");
		}
	}
}
