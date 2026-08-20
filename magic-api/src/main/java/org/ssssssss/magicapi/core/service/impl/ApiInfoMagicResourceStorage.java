package org.ssssssss.magicapi.core.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.service.AbstractPathMagicResourceStorage;
import org.ssssssss.magicapi.utils.PathUtils;

import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ApiInfoMagicResourceStorage extends AbstractPathMagicResourceStorage<ApiInfo> {

	private final String prefix;

	public ApiInfoMagicResourceStorage(String prefix) {
		this.prefix = StringUtils.defaultIfBlank(prefix, "") + "/";
	}

	@Override
	public String folder() {
		return "api";
	}

	@Override
	public Class<ApiInfo> magicClass() {
		return ApiInfo.class;
	}

	@Override
	public String buildMappingKey(ApiInfo info, String path) {
		String fullPath = Stream.of(path, info.getPath())
				.filter(StringUtils::isNotBlank)
				.collect(Collectors.joining("/"));
		return info.getMethod().toUpperCase() + ":" + PathUtils.replaceSlash(fullPath);
	}

	@Override
	public String buildMappingKey(ApiInfo info) {
		String path = Stream.of(this.prefix, magicResourceService.getGroupPath(info.getGroupId()))
				.filter(StringUtils::isNotBlank)
				.collect(Collectors.joining("/"));
		// 若接口路径与父级分组路径均为空，则向上返回空，由上层校验 PATH_REQUIRED
		if (this.prefix.equals(path) && StringUtils.isBlank(info.getPath())) {
			return null;
		}
		return PathUtils.replaceSlash(buildMappingKey(info, path));
	}

	@Override
	public void validate(ApiInfo entity) {
		notBlank(entity.getMethod(), REQUEST_METHOD_REQUIRED);
        notBlank(entity.getScript(), SCRIPT_REQUIRED);
	}
}
