package org.ssssssss.magicapi.modules.http;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Keeps one gateway sign-in per key in the current service process. */
public final class GatewaySignCache {

	static final long VALID_NANOS = TimeUnit.HOURS.toNanos(6);
	private static final GatewaySignCache SHARED = new GatewaySignCache(System::nanoTime);

	private final ConcurrentMap<String, SignIn> entries = new ConcurrentHashMap<>();
	private final LongSupplier clock;

	GatewaySignCache(LongSupplier clock) {
		this.clock = clock;
	}

	public static String getOrSignIn(String key, Function<Void, String> signIn) {
		return SHARED.get(key, signIn);
	}

	String get(String key, Function<Void, String> signIn) {
		return entries.compute(key, (ignored, previous) -> {
			if (previous != null && clock.getAsLong() - previous.startedAt < VALID_NANOS) {
				return previous;
			}
			String signNo = signIn.apply(null);
			if (signNo == null || signNo.isEmpty()) {
				throw new IllegalStateException("医保签到返回空签到号");
			}
			return new SignIn(signNo, clock.getAsLong());
		}).signNo;
	}

	private static final class SignIn {
		private final String signNo;
		private final long startedAt;

		private SignIn(String signNo, long startedAt) {
			this.signNo = signNo;
			this.startedAt = startedAt;
		}
	}
}
