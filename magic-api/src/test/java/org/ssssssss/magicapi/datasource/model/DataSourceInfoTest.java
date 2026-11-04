package org.ssssssss.magicapi.datasource.model;

import org.junit.jupiter.api.Test;
import org.ssssssss.magicapi.utils.JsonUtils;

import static org.junit.jupiter.api.Assertions.*;

class DataSourceInfoTest {

	@Test
	void resourceListSummaryKeepsPrimaryFlagWithoutConnectionDetails() {
		DataSourceInfo info = new DataSourceInfo();
		info.setId("primary-db");
		info.setKey("his");
		info.setPrimary(true);
		info.setUrl("jdbc:example:test");
		info.setUsername("user");
		info.setPassword("password");

		DataSourceInfo summary = JsonUtils.readValue(JsonUtils.toJsonString(info.simple()), DataSourceInfo.class);
		assertNotNull(summary);
		assertTrue(summary.isPrimary());
		assertEquals("primary-db", summary.getId());
		assertEquals("his", summary.getKey());
		assertNull(summary.getUrl());
		assertNull(summary.getUsername());
		assertNull(summary.getPassword());
	}

	@Test
	void resourceListSummaryReflectsClearedPrimaryFlag() {
		DataSourceInfo info = new DataSourceInfo();
		info.setPrimary(true);
		info.setPrimary(false);
		DataSourceInfo summary = JsonUtils.readValue(JsonUtils.toJsonString(info.simple()), DataSourceInfo.class);
		assertNotNull(summary);
		assertFalse(summary.isPrimary());
	}
}
