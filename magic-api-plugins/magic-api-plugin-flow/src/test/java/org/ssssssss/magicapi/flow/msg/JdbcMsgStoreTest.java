package org.ssssssss.magicapi.flow.msg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1：消息表与存储
 */
class JdbcMsgStoreTest {

	private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

	private JdbcTemplate jdbc;

	private JdbcMsgStore store;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:msg-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
		jdbc = new JdbcTemplate(ds);
		store = new JdbcMsgStore(() -> jdbc, "t_", true, new Clock() {
			@Override
			public ZoneId getZone() {
				return ZoneId.systemDefault();
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return Instant.ofEpochMilli(now.get());
			}
		});
	}

	private FlowMessage msg(String type, String msgId, String orderKey) {
		FlowMessage m = new FlowMessage();
		m.setType(type);
		m.setMsgId(msgId);
		m.setOrderKey(orderKey);
		m.setPayload("{\"a\":1}");
		m.setClientIp("10.0.0.1");
		return m;
	}

	@Test
	void receiveCreatesTableAndDeduplicates() {
		JdbcMsgStore.Received first = store.receive(msg("lis", "M1", null));
		JdbcMsgStore.Received again = store.receive(msg("lis", "M1", null));
		store.receive(msg("pacs", "M1", null));

		assertThat(first.isDuplicate()).isFalse();
		assertThat(again.isDuplicate()).isTrue();
		assertThat(again.getMessage().getId()).isEqualTo(first.getMessage().getId());
		assertThat(jdbc.queryForObject("select count(*) from t_msg", Integer.class)).isEqualTo(2);
		FlowMessage saved = store.find(first.getMessage().getId());
		assertThat(saved.getStatus()).isEqualTo(MsgStatus.PENDING);
		assertThat(saved.getPayload()).isEqualTo("{\"a\":1}");
	}

	@Test
	void seqIsStrictlyIncreasingWithinSameMillisecond() {
		long a = store.receive(msg("lis", "A", null)).getMessage().getSeq();
		long b = store.receive(msg("lis", "B", null)).getMessage().getSeq();
		long c = store.receive(msg("lis", "C", null)).getMessage().getSeq();
		assertThat(a).isLessThan(b);
		assertThat(b).isLessThan(c);
	}

	@Test
	void concurrentClaimsGetEachMessageOnce() throws Exception {
		for (int i = 0; i < 50; i++) {
			store.receive(msg("lis", "M" + i, null));
		}
		ExecutorService pool = Executors.newFixedThreadPool(4);
		Set<String> claimed = ConcurrentHashMap.newKeySet();
		AtomicLong duplicates = new AtomicLong();
		List<Future<?>> futures = new ArrayList<>();
		for (int w = 0; w < 4; w++) {
			String owner = "node" + w;
			futures.add(pool.submit(() -> {
				for (String id : store.due("lis", 100)) {
					FlowMessage m = store.claim(id, owner, false, false);
					if (m != null && !claimed.add(m.getId())) {
						duplicates.incrementAndGet();
					}
				}
			}));
		}
		for (Future<?> f : futures) {
			f.get();
		}
		pool.shutdown();
		assertThat(duplicates.get()).isZero();
		assertThat(claimed).hasSize(50);
		assertThat(jdbc.queryForObject("select count(*) from t_msg where status = 'PROCESSING' and attempts = 1", Integer.class)).isEqualTo(50);
	}

	@Test
	void failRetriesThenDeadAndManualRetry() {
		String id = store.receive(msg("lis", "M1", null)).getMessage().getId();
		FlowMessage m = store.claim(id, "me", false, false);
		assertThat(m.getAttempts()).isEqualTo(1);

		Date next = new Date(now.get() + 60_000);
		assertThat(store.fail(id, "me", "接口超时", "call1", "[]", 12, next, false)).isTrue();
		assertThat(store.due("lis", 10)).isEmpty();
		now.addAndGet(60_000);
		assertThat(store.due("lis", 10)).containsExactly(id);

		// 不是领取人不能改状态
		store.claim(id, "me", false, false);
		assertThat(store.success(id, "other", "x", 1, false)).isFalse();
		assertThat(store.fail(id, "me", "还是失败", "call1", "[{}]", 5, null, true)).isTrue();
		FlowMessage dead = store.find(id);
		assertThat(dead.getStatus()).isEqualTo(MsgStatus.DEAD);
		assertThat(dead.getAttempts()).isEqualTo(2);
		assertThat(dead.getTrace()).isEqualTo("[{}]");
		assertThat(dead.getCbStatus()).isEqualTo(MsgStatus.CB_PENDING);

		assertThat(store.retry(Collections.singletonList(id))).isEqualTo(1);
		FlowMessage again = store.claim(id, "me", false, false);
		assertThat(again.getAttempts()).isEqualTo(1);
		assertThat(store.success(id, "me", "{\"ok\":1}", 3, false)).isTrue();
		FlowMessage done = store.find(id);
		assertThat(done.getStatus()).isEqualTo(MsgStatus.SUCCESS);
		assertThat(done.getTrace()).isNull();
		assertThat(done.getError()).isNull();
		assertThat(done.getCbStatus()).isNull();
	}

	@Test
	void timeoutRecovery() {
		String id = store.receive(msg("lis", "M1", null)).getMessage().getId();
		store.claim(id, "crashed", false, false);
		assertThat(store.recoverTimeout(600_000)).isZero();
		now.addAndGet(600_001);
		assertThat(store.recoverTimeout(600_000)).isEqualTo(1);
		FlowMessage m = store.find(id);
		assertThat(m.getStatus()).isEqualTo(MsgStatus.RETRY);
		assertThat(m.getError()).contains("处理超时");
		// 原领取人迟到的结果不再生效
		assertThat(store.success(id, "crashed", null, 1, false)).isFalse();
	}

	@Test
	void orderedHeadsPerGroupAndBlockingDead() {
		String a1 = store.receive(msg("adt", "A1", "P1")).getMessage().getId();
		String b1 = store.receive(msg("adt", "B1", "P2")).getMessage().getId();
		String a2 = store.receive(msg("adt", "A2", "P1")).getMessage().getId();

		assertThat(store.dueHeads("adt", true, 10, 100)).containsExactly(a1, b1);
		// 同组后面的消息不能越过前面的被领取
		assertThat(store.claim(a2, "me", true, true)).isNull();
		assertThat(store.claim(a1, "me", true, true)).isNotNull();
		assertThat(store.dueHeads("adt", true, 10, 100)).containsExactly(b1);

		store.fail(a1, "me", "失败", null, null, 1, null, false);
		// 死信阻塞：P1 组后面的消息不处理
		assertThat(store.dueHeads("adt", true, 10, 100)).containsExactly(b1);
		assertThat(store.claim(a2, "me", true, true)).isNull();
		// 跳过策略：死信不阻塞
		assertThat(store.dueHeads("adt", false, 10, 100)).containsExactly(b1, a2);
		// 人工忽略后放行
		store.ignore(Collections.singletonList(a1));
		assertThat(store.dueHeads("adt", true, 10, 100)).containsExactly(b1, a2);
		assertThat(store.claim(a2, "me", true, true)).isNotNull();
	}

	@Test
	void callbackLifecycle() {
		String id = store.receive(msg("lis", "M1", null)).getMessage().getId();
		store.claim(id, "me", false, false);
		store.success(id, "me", "ok", 1, true);
		assertThat(store.dueCallbacks(10)).containsExactly(id);
		FlowMessage m = store.claimCallback(id, 60_000);
		assertThat(m.getCbAttempts()).isEqualTo(1);
		assertThat(store.claimCallback(id, 60_000)).isNull();
		store.callbackDone(id, false, "连接拒绝", null, new Date(now.get() + 1000));
		assertThat(store.find(id).getCbStatus()).isEqualTo(MsgStatus.CB_RETRY);
		now.addAndGet(1000);
		store.claimCallback(id, 60_000);
		store.callbackDone(id, false, "连接拒绝", null, null);
		assertThat(store.find(id).getCbStatus()).isEqualTo(MsgStatus.CB_FAILED);
		assertThat(store.stats().get("lis").getCallbackFailed()).isEqualTo(1);
		assertThat(store.resendCallback(Collections.singletonList(id))).isEqualTo(1);
		store.claimCallback(id, 60_000);
		store.callbackDone(id, true, null, "<ok/>", null);
		FlowMessage done = store.find(id);
		assertThat(done.getCbStatus()).isEqualTo(MsgStatus.CB_SUCCESS);
		assertThat(done.getCbResponse()).isEqualTo("<ok/>");
	}

	@Test
	void statsPageAndCleanup() {
		String old = store.receive(msg("lis", "OLD", null)).getMessage().getId();
		store.claim(old, "me", false, false);
		store.success(old, "me", null, 1, false);
		now.addAndGet(31L * 24 * 3600 * 1000);
		store.receive(msg("lis", "P1", null));
		store.receive(msg("pacs", "P2", null));

		Map<String, JdbcMsgStore.TypeStats> stats = store.stats();
		assertThat(stats.get("lis").getBacklog()).isEqualTo(1);
		assertThat(stats.get("pacs").getBacklog()).isEqualTo(1);
		assertThat(stats.get("lis").getOldest()).isNotNull();

		JdbcMsgStore.Query query = new JdbcMsgStore.Query();
		query.type = "lis";
		JdbcMsgStore.Page page = store.page(query);
		assertThat(page.getTotal()).isEqualTo(2);
		assertThat(page.getRows()).extracting(FlowMessage::getMsgId).containsExactly("P1", "OLD");
		query.offset = 1;
		assertThat(store.page(query).getRows()).extracting(FlowMessage::getMsgId).containsExactly("OLD");

		assertThat(store.cleanup(new Date(now.get() - 30L * 24 * 3600 * 1000), 1000)).isEqualTo(1);
		assertThat(store.find(old)).isNull();
		assertThat(jdbc.queryForObject("select count(*) from t_msg", Integer.class)).isEqualTo(2);
	}
}
