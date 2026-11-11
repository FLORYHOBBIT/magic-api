package org.ssssssss.magicapi.modules.db.inteceptor;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.core.context.RequestEntity;
import org.ssssssss.magicapi.modules.db.BoundSql;

/**
 * 默认打印SQL实现
 *
 * @author mxd
 */
public class DefaultSqlInterceptor implements SQLInterceptor {

	public void handleLog(BoundSql boundSql, RequestEntity requestEntity) {
		String scriptName = requestEntity == null || requestEntity.getMagicScriptContext() == null
				? null : requestEntity.getMagicScriptContext().getScriptName();
		// 定时任务没有 HTTP 请求上下文，SQL 自身的运行上下文仍保留了脚本名称。
		if (StringUtils.isBlank(scriptName) && boundSql.getRuntimeContext() != null
				&& boundSql.getRuntimeContext().getScriptContext() != null) {
			scriptName = boundSql.getRuntimeContext().getScriptContext().getScriptName();
		}
		Logger logger = LoggerFactory.getLogger(StringUtils.defaultIfBlank(scriptName, "Unknown"));
		String dataSourceName = boundSql.getSqlModule() == null ? null : boundSql.getSqlModule().getDataSourceName();
		String source = StringUtils.isBlank(dataSourceName) ? "" : "[数据源：" + dataSourceName + "] ";
		try {
			logger.info("执行SQL：{}{}", source, SqlLogFormatter.format(boundSql.getSql(), boundSql.getParameters()).trim());
		} catch (RuntimeException e) {
			// 日志展开失败不能影响真实 SQL 执行，也不能掩盖原始数据库异常。
			logger.warn("SQL 日志参数展开失败：{}", e.getMessage());
			logger.info("原始SQL（日志参数未展开）：{}{}", source, boundSql.getSql());
		}
	}

	@Override
	public Object postHandle(BoundSql boundSql, Object result, RequestEntity requestEntity) {
		handleLog(boundSql, requestEntity);
		return result;
	}

	@Override
	public void handleException(BoundSql boundSql, Throwable throwable, RequestEntity requestEntity) {
		handleLog(boundSql, requestEntity);
	}
}
