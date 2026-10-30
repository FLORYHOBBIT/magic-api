package org.ssssssss.magicapi.flow.model;

import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.model.PathMagicEntity;

import java.util.Objects;

/**
 * 集成流程资源。
 * <p>
 * 流程定义（节点、连线、节点配置）以 JSON 字符串保存在 {@link #getScript()} 中，
 * 这样可以直接复用 magic-api 的存储、分组、导入导出与备份。
 */
public class FlowInfo extends PathMagicEntity {

	/**
	 * 是否启用。停用后不注册触发器（HTTP 路由、SOAP 端点、定时调度）。
	 */
	private boolean enabled;

	/**
	 * 流程说明
	 */
	private String description;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@Override
	public FlowInfo copy() {
		FlowInfo info = new FlowInfo();
		super.copyTo(info);
		info.setEnabled(this.enabled);
		info.setDescription(this.description);
		return info;
	}

	@Override
	public MagicEntity simple() {
		FlowInfo info = new FlowInfo();
		super.simple(info);
		return info;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		FlowInfo that = (FlowInfo) o;
		return Objects.equals(id, that.id) &&
				Objects.equals(path, that.path) &&
				Objects.equals(script, that.script) &&
				Objects.equals(name, that.name) &&
				Objects.equals(description, that.description) &&
				enabled == that.enabled;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, path, script, name, groupId, enabled, description);
	}
}
