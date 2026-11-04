package org.ssssssss.magicapi.datasource.model;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class MagicDynamicDataSourceTest {

	@Test
	void blankKeyUsesDefaultWhenNoPrimary() {
		MagicDynamicDataSource ds = new MagicDynamicDataSource();
		DataSource def = mock(DataSource.class);
		DataSource other = mock(DataSource.class);
		ds.put(null, null, null, def, -1);
		ds.put("id-a", "a", "A", other, -1);
		assertSame(def, ds.getDataSource(null).getDataSource());
		assertSame(def, ds.getDataSource("").getDataSource());
		assertSame(other, ds.getDataSource("a").getDataSource());
	}

	@Test
	void blankKeyUsesPrimaryWhenSet() {
		MagicDynamicDataSource ds = new MagicDynamicDataSource();
		DataSource def = mock(DataSource.class);
		DataSource primary = mock(DataSource.class);
		ds.put(null, null, null, def, -1);
		ds.put("id-p", "his", "HIS", primary, -1);
		ds.setPrimaryKey("his");
		assertSame(primary, ds.getDataSource().getDataSource());
		assertSame(primary, ds.getDataSource("").getDataSource());
	}

	@Test
	void clearingPrimaryFallsBackToDefault() {
		MagicDynamicDataSource ds = new MagicDynamicDataSource();
		DataSource def = mock(DataSource.class);
		DataSource primary = mock(DataSource.class);
		ds.put(null, null, null, def, -1);
		ds.put("id-p", "his", "HIS", primary, -1);
		ds.setPrimaryKey("his");
		ds.setPrimaryKey(null);
		assertSame(def, ds.getDataSource().getDataSource());
		assertNull(ds.getPrimaryKey());
	}
}
