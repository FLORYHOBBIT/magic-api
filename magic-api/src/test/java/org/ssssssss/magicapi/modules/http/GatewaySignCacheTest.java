package org.ssssssss.magicapi.modules.http;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GatewaySignCacheTest {

	@Test
	void expiresAtSixHoursAndIsolatesKeys() {
		AtomicLong clock = new AtomicLong();
		GatewaySignCache cache = new GatewaySignCache(clock::get);
		assertEquals("first", cache.get("staff-a", ignored -> "first"));
		assertEquals("other", cache.get("staff-b", ignored -> "other"));
		clock.set(TimeUnit.HOURS.toNanos(6) - 1);
		assertEquals("first", cache.get("staff-a", ignored -> "second"));
		clock.incrementAndGet();
		assertEquals("second", cache.get("staff-a", ignored -> "second"));
	}

	@Test
	void onlyOneCallerSignsInForAKey() throws Exception {
		GatewaySignCache cache = new GatewaySignCache(System::nanoTime);
		ExecutorService pool = Executors.newFixedThreadPool(4);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger requests = new AtomicInteger();
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return cache.get("staff-a", ignored -> "sign-" + requests.incrementAndGet());
				}));
			}
			start.countDown();
			for (Future<String> future : futures) {
				assertEquals("sign-1", future.get(5, TimeUnit.SECONDS));
			}
			assertEquals(1, requests.get());
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aFailedSignInIsNotCached() {
		GatewaySignCache cache = new GatewaySignCache(System::nanoTime);
		assertThrows(IllegalStateException.class, () -> cache.get("staff-a", ignored -> null));
		assertEquals("recovered", cache.get("staff-a", ignored -> "recovered"));
	}
}
