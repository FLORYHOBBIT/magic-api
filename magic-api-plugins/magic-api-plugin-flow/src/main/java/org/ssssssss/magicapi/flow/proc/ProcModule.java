package org.ssssssss.magicapi.flow.proc;

import org.ssssssss.magicapi.core.annotation.MagicModule;
import org.ssssssss.script.annotation.Comment;

import java.util.*;

/**
 * 脚本调用存储过程：import proc 后使用。返回出参 Map，接口可直接 return，或用 response.text(...) 原样返回。
 * <pre>
 *   var r = proc.call('pkg.op_invoke', {as_method: 'getHospitalInfo', ab_in_parm: body})
 *   return response.text(r.rb_out_parm)
 *
 *   proc.use('his').call('pkg.p', {a: 1}, {rows_json: 'json'})
 *   proc.call({mode: 'sql', sql: 'begin pkg.p(:a, :r); end;', params: [{name:'a', value:1}, {name:'r', mode:'out', type:'cursor'}]})
 * </pre>
 */
@MagicModule("proc")
public class ProcModule {

	private final ProcService service;

	private final String datasource;

	public ProcModule(ProcService service) {
		this(service, null);
	}

	private ProcModule(ProcService service, String datasource) {
		this.service = service;
		this.datasource = datasource;
	}

	@Comment("切换数据源，如 proc.use('his').call(...)")
	public ProcModule use(@Comment(name = "datasource", value = "数据源 key，空为主数据源") String datasource) {
		return new ProcModule(service, datasource);
	}

	@Comment("调用存储过程，按数据库中的参数定义自动注册出参，返回出参 Map；没给的入参有默认值时不传")
	public Map<String, Object> call(@Comment(name = "name", value = "过程名，如 pkg.proc") String name,
									@Comment(name = "inputs", value = "入参，如 {as_method: 'x', ab_in_parm: body}") Map<String, Object> inputs) {
		return service.call(datasource, name, inputs, null);
	}

	@Comment("调用存储过程，并指定出参处理方式，如 {rb_out_parm: 'xml'}（string / json / xml）")
	public Map<String, Object> call(@Comment(name = "name", value = "过程名，如 pkg.proc") String name,
									@Comment(name = "inputs", value = "入参") Map<String, Object> inputs,
									@Comment(name = "parses", value = "出参处理方式，如 {rb_out_parm: 'json'}") Map<String, Object> parses) {
		Map<String, String> p = new LinkedHashMap<>();
		if (parses != null) {
			parses.forEach((k, v) -> p.put(k, v == null ? null : v.toString()));
		}
		return service.call(datasource, name, inputs, p.isEmpty() ? null : p);
	}

	@Comment("按完整描述调用：{name 或 sql, mode: form/sql, params: [{name, mode, type, value, parse}], returnType, columnCase, timeout}")
	public Map<String, Object> call(@Comment(name = "spec", value = "调用描述，格式同流程节点「调用存储过程」") Map<String, Object> spec) {
		Map<String, Object> config = new LinkedHashMap<>(spec);
		if (config.get("datasource") == null && datasource != null) {
			config.put("datasource", datasource);
		}
		return service.call(ProcSpec.from(config, ProcSpec.LITERAL));
	}

	@Comment("查看过程的参数定义（每个重载一组）：name、mode、type、dataType、defaulted")
	public List<List<Map<String, Object>>> arguments(@Comment(name = "name", value = "过程名，如 pkg.proc") String name) {
		return service.arguments(datasource, name, false);
	}
}
