package org.ssssssss.magicapi.flow.engine.node;

import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.engine.FlowNodeHandler;
import org.ssssssss.magicapi.modules.db.SQLModule;
import org.ssssssss.magicapi.modules.db.Transaction;

import java.util.*;

import static org.ssssssss.magicapi.flow.FlowJsonCodes.FLOW_NODE_CONFIG_INVALID;

/**
 * 写库节点：执行 insert / update / delete / merge 语句。
 * <pre>
 * config:
 *   datasource   数据源 key，空为主数据源
 *   sql          写入语句，支持 #{} 参数；逐行执行时可用 row、index
 *   forEach      可选，表达式，结果为列表时逐行执行，如 q1 或 prev
 *   transaction  逐行执行时是否放在一个事务中，默认 true（任一行失败全部回滚）
 * 输出：{affected: 受影响行数合计, executed: 执行次数}
 * </pre>
 * 按表字段映射写入（不写 SQL）在表对表同步任务中提供。
 */
public class WriteNodeHandler extends AbstractSqlNodeHandler implements FlowNodeHandler {

	public static final String TYPE = "write";

	public WriteNodeHandler(SQLModule sqlModule) {
		super(sqlModule);
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void check(FlowNodeContext node) {
		checkSql(node);
	}

	@Override
	public Object execute(FlowNodeContext node) {
		SQLModule module = module(node);
		String sql = node.requireString("sql");
		String forEach = node.string("forEach");
		int affected = 0;
		int executed = 0;
		if (forEach == null) {
			affected = module.update(bind(module, node, sql, null));
			executed = 1;
		} else {
			Collection<?> rows = rows(node, node.evaluate(forEach));
			Transaction transaction = node.bool("transaction", true) && !rows.isEmpty() ? module.transaction() : null;
			try {
				int index = 0;
				for (Object row : rows) {
					Map<String, Object> extra = new HashMap<>();
					extra.put("row", row);
					extra.put("index", index++);
					try {
						affected += module.update(bind(module, node, sql, extra));
					} catch (RuntimeException e) {
						throw new IllegalStateException("第 " + index + " 行写入失败", e);
					}
					executed++;
				}
				if (transaction != null) {
					transaction.commit();
				}
			} catch (RuntimeException e) {
				if (transaction != null) {
					transaction.rollback();
				}
				throw e;
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("affected", affected);
		result.put("executed", executed);
		return result;
	}

	private Collection<?> rows(FlowNodeContext node, Object value) {
		if (value == null) {
			return Collections.emptyList();
		}
		if (value instanceof Collection) {
			return (Collection<?>) value;
		}
		if (value instanceof Map) {
			return Collections.singletonList(value);
		}
		if (value.getClass().isArray() && value instanceof Object[]) {
			return Arrays.asList((Object[]) value);
		}
		throw new InvalidArgumentException(FLOW_NODE_CONFIG_INVALID.format(node.label(), "forEach", "结果不是列表，而是 " + value.getClass().getSimpleName()));
	}
}
