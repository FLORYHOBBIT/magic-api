package org.ssssssss.magicapi.core.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.resource.FileResource;
import org.ssssssss.magicapi.core.servlet.MagicRequestContextHolder;
import org.ssssssss.magicapi.function.service.FunctionInfoMagicResourceStorage;
import org.ssssssss.magicapi.utils.JsonUtils;
import org.ssssssss.magicapi.utils.WebUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DefaultMagicResourceServiceTest {

	@TempDir Path root;
	private DefaultMagicResourceService service;
	private MagicRequestContextHolder previousHolder;

	@BeforeEach
	void setUp() {
		previousHolder = WebUtils.magicRequestContextHolder;
		WebUtils.magicRequestContextHolder = mock(MagicRequestContextHolder.class);
		service = new DefaultMagicResourceService(
				new FileResource(root.toFile(), false, root.toString()),
				Arrays.asList(new ApiInfoMagicResourceStorage(""), new FunctionInfoMagicResourceStorage()),
				event -> {});
		service.refresh();
	}

	@AfterEach
	void restoreHolder() {
		WebUtils.magicRequestContextHolder = previousHolder;
	}

	@Test
	void renamingGroupThenScriptKeepsFileMappingsAndPersistsContent() {
		Group group = group("api", "before", "/insur/trace", "0");
		ApiInfo api = api(group, "upload_3503", "/upload_3503");
		Group renamed = copy(group);
		renamed.setName("追溯码上传");
		assertTrue(service.saveGroup(renamed));

		ApiInfo changed = api.copy();
		changed.setName("商品采购1");
		changed.setScript("return 2;");
		assertTrue(service.saveFile(changed));
		assertFalse(Files.exists(root.resolve("api/before")));
		assertFalse(Files.exists(root.resolve("api/追溯码上传/upload_3503.ms")));
		assertTrue(Files.exists(root.resolve("api/追溯码上传/商品采购1.ms")));

		service.refresh();
		ApiInfo saved = service.file(api.getId());
		assertEquals("商品采购1", saved.getName());
		assertEquals("return 2;", saved.getScript());
	}

	@Test
	void sameGroupNameAcrossModulesIsAllowedButSiblingNamesMustBeUnique() {
		group("api", "追溯码上传", "/insur/trace", "0");
		group("function", "追溯码上传", "/insur/common", "0");
		InvalidArgumentException duplicate = assertThrows(InvalidArgumentException.class,
				() -> group("api", "追溯码上传", "/other", "0"));
		assertTrue(duplicate.getMessage().contains("同一模块"));
		assertTrue(duplicate.getMessage().contains("追溯码上传"));
	}

	@Test
	void renamingScriptToExistingNameDoesNotOverwriteEitherScript() {
		Group group = group("api", "uploads", "/insur/trace", "0");
		ApiInfo first = api(group, "first", "/first");
		ApiInfo second = api(group, "second", "/second");
		ApiInfo changed = first.copy();
		changed.setName("second");
		changed.setScript("return 999;");
		InvalidArgumentException conflict = assertThrows(InvalidArgumentException.class,
				() -> service.saveFile(changed));
		assertTrue(conflict.getMessage().contains("脚本名称"));
		assertTrue(conflict.getMessage().contains("second"));
		service.refresh();
		assertEquals("return 1;", service.<ApiInfo>file(first.getId()).getScript());
		assertEquals("return 1;", service.<ApiInfo>file(second.getId()).getScript());
	}

	@Test
	void invalidGroupNameIsRejectedBeforeCreatingResources() {
		InvalidArgumentException invalid = assertThrows(InvalidArgumentException.class,
				() -> group("api", "bad?name", "/test", "0"));
		assertEquals(JsonCodeConstants.NAME_INVALID.getCode(), invalid.getCode());
	}

	@Test
	void nestedMappingsAreRebuiltAfterReloadAndParentRename() {
		Group parent = group("api", "parent", "/insur", "0");
		Group child = group("api", "child", "/trace", parent.getId());
		ApiInfo api = api(child, "upload", "/upload");
		service.refresh();
		Group renamed = copy(parent);
		renamed.setName("renamed");
		assertTrue(service.saveGroup(renamed));
		ApiInfo changed = api.copy();
		changed.setName("updated");
		assertTrue(service.saveFile(changed));
		assertTrue(Files.exists(root.resolve("api/renamed/child/updated.ms")));
		ApiInfo conflict = api.copy();
		conflict.setId(null);
		conflict.setName("duplicate-path");
		InvalidArgumentException duplicate = assertThrows(InvalidArgumentException.class,
				() -> service.saveFile(conflict));
		assertEquals(JsonCodeConstants.PATH_CONFLICT.getCode(), duplicate.getCode());
	}

	@Test
	void lockedScriptRejectsStaleUnlockedPayloadAndKeepsDiskAndCacheUnchanged() throws Exception {
		Group group = group("api", "locked", "/locked", "0");
		ApiInfo api = api(group, "upload", "/upload");
		ApiInfo stalePayload = api.copy();
		assertTrue(service.lock(api.getId()));
		Path file = root.resolve("api/locked/upload.ms");
		byte[] lockedBytes = Files.readAllBytes(file);
		stalePayload.setLock("0");
		stalePayload.setScript("return 999;");
		InvalidArgumentException blocked = assertThrows(InvalidArgumentException.class,
				() -> service.saveFile(stalePayload));
		assertEquals(JsonCodeConstants.RESOURCE_LOCKED.getCode(), blocked.getCode());
		assertArrayEquals(lockedBytes, Files.readAllBytes(file));
		assertEquals("return 1;", service.<ApiInfo>file(api.getId()).getScript());
		service.refresh();
		assertEquals("return 1;", service.<ApiInfo>file(api.getId()).getScript());
		assertEquals("1", service.<ApiInfo>file(api.getId()).getLock());
		assertTrue(service.unlock(api.getId()));
		assertTrue(service.saveFile(stalePayload));
		service.refresh();
		assertEquals("return 999;", service.<ApiInfo>file(api.getId()).getScript());
	}

	@Test
	void folderLockIncludesNestedScriptsButLeavesOtherFoldersAndModulesUntouched() throws Exception {
		Group parent = group("api", "batch", "/batch", "0");
		Group nested = group("api", "nested", "/nested", parent.getId());
		Group other = group("api", "other", "/other", "0");
		ApiInfo first = api(parent, "one", "/one"), second = api(nested, "two", "/two"), outside = api(other, "three", "/three");
		assertTrue(service.lock(first.getId()));
		java.util.Map<String, Object> result = service.lockGroup(parent.getId(), true, entity -> true);
		assertEquals(2, result.get("total")); assertEquals(1, result.get("changed")); assertEquals(1, result.get("unchanged")); assertEquals(0, result.get("failed"));
		byte[] unchanged = Files.readAllBytes(root.resolve("api/batch/nested/two.ms"));
		assertEquals(0, service.lockGroup(parent.getId(), true, entity -> true).get("changed"));
		assertArrayEquals(unchanged, Files.readAllBytes(root.resolve("api/batch/nested/two.ms")));
		service.refresh();
		assertEquals("1", service.<ApiInfo>file(first.getId()).getLock()); assertEquals("1", service.<ApiInfo>file(second.getId()).getLock());
		assertNotEquals("1", service.<ApiInfo>file(outside.getId()).getLock());
		assertEquals("return 1;", service.<ApiInfo>file(second.getId()).getScript());
		assertEquals("/two", service.<ApiInfo>file(second.getId()).getPath());
		assertEquals(2, service.lockGroup(parent.getId(), false, entity -> true).get("changed"));
		service.refresh(); assertEquals("0", service.<ApiInfo>file(second.getId()).getLock());
	}

	@Test
	void folderLockChecksEveryPermissionBeforeAnyWrite() throws Exception {
		Group group = group("api", "denied", "/denied", "0");
		ApiInfo one = api(group, "one", "/one"), two = api(group, "two", "/two");
		byte[] beforeOne = Files.readAllBytes(root.resolve("api/denied/one.ms"));
		byte[] beforeTwo = Files.readAllBytes(root.resolve("api/denied/two.ms"));
		assertThrows(InvalidArgumentException.class, () -> service.lockGroup(group.getId(), true, entity -> !entity.getId().equals(two.getId())));
		assertArrayEquals(beforeOne, Files.readAllBytes(root.resolve("api/denied/one.ms")));
		assertArrayEquals(beforeTwo, Files.readAllBytes(root.resolve("api/denied/two.ms")));
		assertNotEquals("1", service.<ApiInfo>file(one.getId()).getLock());
	}

	@Test
	void emptyFolderAndMissingFolderProduceExplicitResults() {
		Group group = group("api", "empty", "/empty", "0");
		assertEquals(0, service.lockGroup(group.getId(), true, entity -> true).get("total"));
		assertThrows(InvalidArgumentException.class, () -> service.lockGroup("missing", true, entity -> true));
	}

	@Test
	@SuppressWarnings("unchecked")
	void failedLockWriteDoesNotChangeCacheAndBatchReportsFailure() throws Exception {
		Group group = group("api", "failure", "/failure", "0");
		ApiInfo api = api(group, "one", "/one");
		java.lang.reflect.Field field = DefaultMagicResourceService.class.getDeclaredField("fileMappings");
		field.setAccessible(true);
		java.util.Map<String, org.ssssssss.magicapi.core.resource.Resource> mappings = (java.util.Map<String, org.ssssssss.magicapi.core.resource.Resource>) field.get(service);
		org.ssssssss.magicapi.core.resource.Resource resource = org.mockito.Mockito.spy(mappings.get(api.getId()));
		org.mockito.Mockito.doReturn(false).when(resource).write(org.mockito.ArgumentMatchers.any(byte[].class));
		mappings.put(api.getId(), resource);
		assertFalse(service.lock(api.getId()));
		assertNotEquals("1", service.<ApiInfo>file(api.getId()).getLock());
		java.util.Map<String,Object> result = service.lockGroup(group.getId(), true, entity -> true);
		assertEquals(1, result.get("failed")); assertEquals(0, result.get("changed"));
		assertEquals(java.util.Collections.emptyList(), result.get("ids"));
		service.refresh(); assertNotEquals("1", service.<ApiInfo>file(api.getId()).getLock());
	}

	private Group group(String type, String name, String path, String parentId) {
		Group group = new Group();
		group.setType(type);
		group.setName(name);
		group.setPath(path);
		group.setParentId(parentId);
		assertTrue(service.saveGroup(group));
		return group;
	}

	private ApiInfo api(Group group, String name, String path) {
		ApiInfo api = new ApiInfo();
		api.setGroupId(group.getId());
		api.setName(name);
		api.setPath(path);
		api.setMethod("POST");
		api.setScript("return 1;");
		assertTrue(service.saveFile(api));
		return api;
	}

	private Group copy(Group group) {
		return JsonUtils.readValue(JsonUtils.toJsonString(group), Group.class);
	}
}
