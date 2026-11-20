package org.ssssssss.magicapi.datasource.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.StreamUtils;
import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.interceptor.Authorization;
import org.ssssssss.magicapi.core.interceptor.AuthorizationInterceptor;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.model.SelectedResource;
import org.ssssssss.magicapi.core.resource.FileResource;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.core.service.impl.DefaultMagicResourceService;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import org.ssssssss.magicapi.core.servlet.MagicRequestContextHolder;
import org.ssssssss.magicapi.core.web.MagicResourceController;
import org.ssssssss.magicapi.datasource.model.DataSourceInfo;
import org.ssssssss.magicapi.datasource.service.DataSourceInfoMagicResourceStorage;
import org.ssssssss.magicapi.datasource.service.DataSourceMagicDynamicRegistry;
import org.ssssssss.magicapi.utils.JsonUtils;
import org.ssssssss.magicapi.utils.WebUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataSourcePasswordTest {

	private static final String PASSWORD = "fixture-db-password";
	private static final String URL = "jdbc:password-test:saved";
	private static final String GROUP = "datasource:0";

	@TempDir Path root;
	private final MagicConfiguration configuration = new MagicConfiguration();
	private final MagicHttpServletRequest request = mock(MagicHttpServletRequest.class);
	private final TestDriver driver = new TestDriver();
	private MagicResourceService previousService;
	private MagicRequestContextHolder previousHolder;
	private DefaultMagicResourceService service;
	private MagicResourceController resources;
	private MagicDataSourceController dataSources;
	private DataSourceInfo saved;

	@BeforeEach
	void setup() throws SQLException {
		previousService = MagicConfiguration.getMagicResourceService();
		previousHolder = WebUtils.magicRequestContextHolder;
		WebUtils.magicRequestContextHolder = mock(MagicRequestContextHolder.class);
		DataSourceInfoMagicResourceStorage storage = new DataSourceInfoMagicResourceStorage();
		service = new DefaultMagicResourceService(new FileResource(root.toFile(), false, root.toString()),
				Collections.singletonList(storage), event -> {});
		service.refresh();
		configuration.setMagicResourceService(service);
		configuration.setMagicDynamicRegistries(Collections.singletonList(new DataSourceMagicDynamicRegistry(storage, null)));
		configuration.setAuthorizationInterceptor(new AuthorizationInterceptor() {});
		resources = new MagicResourceController(configuration);
		dataSources = new MagicDataSourceController(configuration);

		saved = new DataSourceInfo();
		saved.setName("source");
		saved.setGroupId(GROUP);
		saved.setKey("test");
		saved.setUrl(URL);
		saved.setUsername("fixture-user");
		saved.setPassword(PASSWORD);
		saved.setDriverClassName(TestDriver.class.getName());
		saved.setPrimary(true);
		assertTrue(service.saveFile(saved));
		DriverManager.registerDriver(driver);
	}

	@AfterEach
	void restore() throws SQLException {
		DriverManager.deregisterDriver(driver);
		configuration.setMagicResourceService(previousService);
		WebUtils.magicRequestContextHolder = previousHolder;
	}

	@Test
	void detailOmitsPasswordAndSavingBlankKeepsItOnDisk() throws Exception {
		String json = JsonUtils.toJsonString(resources.detail(saved.getId(), request));
		assertFalse(json.contains("\"password\""));
		assertFalse(json.contains(PASSWORD));
		DataSourceInfo update = (DataSourceInfo) resources.detail(saved.getId(), request).getData();
		assertEquals(URL, update.getUrl());
		assertEquals(PASSWORD, service.<DataSourceInfo>file(saved.getId()).getPassword());
		update.setPassword("");
		update.setMaxRows(100);
		save(update);
		service.refresh();
		assertEquals(PASSWORD, service.<DataSourceInfo>file(saved.getId()).getPassword());
		assertEquals(100, service.<DataSourceInfo>file(saved.getId()).getMaxRows());

		update.setPassword("replacement-password");
		save(update);
		service.refresh();
		assertEquals("replacement-password", service.<DataSourceInfo>file(saved.getId()).getPassword());
		assertNull(((DataSourceInfo) resources.detail(saved.getId(), request).getData()).getPassword());
	}

	@Test
	void savedPasswordWorksForTheSavedTargetOnly() throws Exception {
		DataSourceInfo update = saved.withoutPassword();
		assertEquals("ok", dataSources.test(update, request).getData());
		update.setUrl("jdbc:password-test:changed");
		assertThrows(InvalidArgumentException.class, () -> dataSources.test(update, request));
		InvalidArgumentException error = assertThrows(InvalidArgumentException.class, () -> save(update));
		assertEquals(JsonCodeConstants.DS_PASSWORD_REQUIRED_FOR_CONNECTION_CHANGE.getCode(), error.getCode());
		assertEquals(1, driver.attempts);
		update.setPassword("wrong-password");
		assertFalse(dataSources.test(update, request).getData().contains(update.getPassword()));
		assertEquals(2, driver.attempts);
	}

	@Test
	void copyUsesServerSidePasswordAndKeepsExistingPermissionChecks() {
		DataSourceInfo input = new DataSourceInfo();
		input.setId(saved.getId());
		input.setName("copied");
		AuthorizationInterceptor auth = mock(AuthorizationInterceptor.class);
		configuration.setAuthorizationInterceptor(auth);
		assertThrows(InvalidArgumentException.class, () -> dataSources.test(saved.withoutPassword(), request));
		assertThrows(InvalidArgumentException.class, () -> dataSources.copy(input, request));
		when(auth.allowVisit(null, request, Authorization.VIEW, saved)).thenReturn(true);
		when(auth.allowVisit(isNull(), eq(request), eq(Authorization.SAVE), any(MagicEntity.class))).thenReturn(true);
		DataSourceInfo summary = (DataSourceInfo) dataSources.copy(input, request).getData();
		assertEquals("test_copy", summary.getKey());
		assertNull(summary.getPassword());
		assertFalse(summary.isPrimary());
		assertEquals(PASSWORD, service.<DataSourceInfo>file(summary.getId()).getPassword());
	}

	@Test
	void workbenchArchivesDropPasswordAndCanBeReimportedLocally() throws Exception {
		SelectedResource selection = new SelectedResource();
		selection.setId(saved.getId());
		selection.setType("file");
		verifyArchive(null, Collections.singletonList(selection));
		verifyArchive(GROUP, null);
		byte[] archive = verifyArchive(null, null);
		assertTrue(service.upload(new ByteArrayInputStream(archive), false));
		assertEquals(PASSWORD, service.<DataSourceInfo>file(saved.getId()).getPassword());
		assertTrue(service.upload(new ByteArrayInputStream(archive), true));
		assertEquals(PASSWORD, service.<DataSourceInfo>file(saved.getId()).getPassword());
	}

	private byte[] verifyArchive(String groupId, List<SelectedResource> selection) throws Exception {
		ByteArrayOutputStream original = new ByteArrayOutputStream();
		service.export(groupId, selection, original);
		assertTrue(readDataSource(original.toByteArray()).contains(PASSWORD));
		ByteArrayOutputStream sanitized = new ByteArrayOutputStream();
		DataSourceInfoMagicResourceStorage.stripExportPasswords(new ByteArrayInputStream(original.toByteArray()), sanitized, GROUP.equals(groupId));
		String json = readDataSource(sanitized.toByteArray());
		assertFalse(json.contains("\"password\""));
		assertFalse(json.contains(PASSWORD));
		assertEquals(URL, JsonUtils.readValue(json, DataSourceInfo.class).getUrl());
		return sanitized.toByteArray();
	}

	private String readDataSource(byte[] archive) throws Exception {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().endsWith("source.json")) {
					return StreamUtils.copyToString(zip, StandardCharsets.UTF_8);
				}
			}
			throw new AssertionError("Archive is missing the data source");
		}
	}

	private void save(DataSourceInfo update) throws Exception {
		when(request.getInputStream()).thenReturn(new ByteArrayInputStream(JsonUtils.toJsonBytes(update)));
		assertEquals(saved.getId(), resources.saveFile("datasource", "0", request).getData());
	}

	public static class TestDriver implements Driver {
		private int attempts;

		@Override
		public Connection connect(String url, Properties info) throws SQLException {
			if (!acceptsURL(url)) {
				return null;
			}
			attempts++;
			if (!Objects.equals(PASSWORD, info.getProperty("password"))) {
				throw new SQLException("Fixture driver error for " + info.getProperty("password"));
			}
			return mock(Connection.class);
		}

		@Override public boolean acceptsURL(String url) { return url.startsWith("jdbc:password-test:"); }
		@Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
		@Override public int getMajorVersion() { return 1; }
		@Override public int getMinorVersion() { return 0; }
		@Override public boolean jdbcCompliant() { return false; }
		@Override public Logger getParentLogger() { return Logger.getGlobal(); }
	}
}
