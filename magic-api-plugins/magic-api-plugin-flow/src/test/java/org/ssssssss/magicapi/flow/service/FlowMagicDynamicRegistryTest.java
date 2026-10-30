package org.ssssssss.magicapi.flow.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.flow.model.FlowInfo;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ssssssss.magicapi.flow.service.FlowInfoMagicResourceStorageTest.SIMPLE;
import static org.ssssssss.magicapi.flow.service.FlowInfoMagicResourceStorageTest.flow;

class FlowMagicDynamicRegistryTest {

	private final List<String> events = new ArrayList<>();

	private FlowMagicDynamicRegistry registry;

	@BeforeEach
	void setUp() {
		FlowInfoMagicResourceStorage storage = new FlowInfoMagicResourceStorage();
		MagicResourceService service = mock(MagicResourceService.class);
		when(service.getGroupPath(any())).thenReturn("his");
		storage.setMagicResourceService(service);
		registry = new FlowMagicDynamicRegistry(storage, new FlowTriggerRegistrar() {
			@Override
			public Object register(FlowInfo info, String mappingKey) {
				events.add("register " + mappingKey);
				return "handle:" + mappingKey;
			}

			@Override
			public void unregister(FlowInfo info, Object handle) {
				events.add("unregister " + handle);
			}
		});
	}

	@Test
	void disabledFlowIsIndexedButHasNoTrigger() {
		FlowInfo info = flow("settle", SIMPLE);

		registry.register(info);

		assertThat(registry.getMapping("/his/settle")).isSameAs(info);
		assertThat(registry.getMapping(info.getId())).isSameAs(info);
		assertThat(events).isEmpty();
	}

	@Test
	void enableDisableAndContentChangeReRegisterTrigger() {
		FlowInfo info = flow("settle", SIMPLE);
		registry.register(info);

		FlowInfo enabled = info.copy();
		enabled.setEnabled(true);
		registry.register(enabled);
		assertThat(events).containsExactly("register /his/settle");

		// 同一路径、内容变化：先注销再注册，触发器拿到新配置
		FlowInfo changed = enabled.copy();
		changed.setScript("{}");
		registry.register(changed);
		assertThat(registry.getMapping("/his/settle").getScript()).isEqualTo("{}");

		// 内容不变：不重复注册
		registry.register(changed.copy());

		FlowInfo disabled = changed.copy();
		disabled.setEnabled(false);
		registry.register(disabled);

		assertThat(events).containsExactly(
				"register /his/settle",
				"unregister handle:/his/settle",
				"register /his/settle",
				"unregister handle:/his/settle");
	}

	@Test
	void renameAndDeleteUnregisterOldTrigger() {
		FlowInfo info = flow("settle", SIMPLE);
		info.setEnabled(true);
		registry.register(info);

		FlowInfo renamed = info.copy();
		renamed.setPath("settle-v2");
		registry.register(renamed);
		assertThat(registry.getMapping("/his/settle")).isNull();
		assertThat(registry.getMapping("/his/settle-v2")).isNotNull();

		registry.unregister(renamed);
		assertThat(registry.getMapping("/his/settle-v2")).isNull();
		assertThat(events).containsExactly(
				"register /his/settle",
				"unregister handle:/his/settle",
				"register /his/settle-v2",
				"unregister handle:/his/settle-v2");
	}

	@Test
	void triggerFailureDoesNotBreakRegistration() {
		FlowInfoMagicResourceStorage storage = new FlowInfoMagicResourceStorage();
		MagicResourceService service = mock(MagicResourceService.class);
		when(service.getGroupPath(any())).thenReturn("his");
		storage.setMagicResourceService(service);
		FlowMagicDynamicRegistry failing = new FlowMagicDynamicRegistry(storage, new FlowTriggerRegistrar() {
			@Override
			public Object register(FlowInfo info, String mappingKey) {
				throw new IllegalStateException("路径冲突");
			}

			@Override
			public void unregister(FlowInfo info, Object handle) {
			}
		});
		FlowInfo info = flow("settle", SIMPLE);
		info.setEnabled(true);

		failing.register(info);

		assertThat(failing.getMapping("/his/settle")).isSameAs(info);
		failing.unregister(info);
		assertThat(failing.getMapping("/his/settle")).isNull();
	}
}
