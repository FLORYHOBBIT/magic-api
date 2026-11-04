package org.ssssssss.magicapi.core.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionRecordStoreTest {

	@TempDir
	Path dir;

	@Test
	void keepsOnlyLatestRecordsNewestFirst() throws Exception {
		ExecutionRecordStore store = new ExecutionRecordStore(dir, 3);
		for (int i = 1; i <= 5; i++) {
			store.append(new ExecutionRecord("abc", "task", "/t", "cron", i, 1, true, null));
		}
		List<ExecutionRecord> records = store.list("abc");
		assertEquals(3, records.size());
		assertEquals(5, records.get(0).getStartTime());
		assertEquals(3, records.get(2).getStartTime());
	}

	@Test
	void recordsAreIsolatedPerResource() throws Exception {
		ExecutionRecordStore store = new ExecutionRecordStore(dir, 10);
		store.append(new ExecutionRecord("one", "api", "/a", "test", 1, 1, false, "boom"));
		store.append(new ExecutionRecord("two", "upload", "/b", "test", 2, 1, true, null));
		List<ExecutionRecord> one = store.list("one");
		assertEquals(1, one.size());
		assertFalse(one.get(0).isSuccess());
		assertEquals("boom", one.get(0).getMessage());
		assertEquals(1, store.list("two").size());
	}

	@Test
	void unknownOrBlankIdReturnsEmpty() throws Exception {
		ExecutionRecordStore store = new ExecutionRecordStore(dir, 10);
		assertTrue(store.list("missing").isEmpty());
		assertTrue(store.list("").isEmpty());
		assertTrue(store.list(null).isEmpty());
	}

	@Test
	void unsafeIdCharactersAreSanitized() throws Exception {
		ExecutionRecordStore store = new ExecutionRecordStore(dir, 10);
		store.append(new ExecutionRecord("../x/y", "task", "/t", "test", 1, 1, true, null));
		assertEquals(1, store.list("../x/y").size());
		assertTrue(dir.resolve("___x_y.jsonl").toFile().exists());
	}
}
