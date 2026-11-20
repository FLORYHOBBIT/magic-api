package org.ssssssss.magicapi.core.trace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.ssssssss.magicapi.core.config.TraceUploadQueryConfig;
import org.ssssssss.magicapi.core.trace.TraceQueryProfiles.Profile;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Reusable SQL wrapper: parameterized filters, aggregate counts and window-function pagination. */
public final class TraceUploadFlagQuery {
    public static class Query {
        public String profileId, startDate, endDate, uploaded = "", keyword = "";
        public int page = 1, pageSize = 50;
    }
    private final TraceUploadQueryConfig config;
    public TraceUploadFlagQuery(TraceUploadQueryConfig config) { this.config = config; }

    public Map<String,Object> execute(Profile profile, Query request, DataSource source) {
        TraceQueryProfiles.validate(profile);
        if (request == null || request.page < 1 || request.page > 1000000 || !Arrays.asList(20,50,100).contains(request.pageSize)) throw new IllegalArgumentException("页码或每页数量无效");
        LocalDate start, end;
        try { start = LocalDate.parse(request.startDate); end = LocalDate.parse(request.endDate); }
        catch (RuntimeException error) { throw new IllegalArgumentException("开始和结束日期必须为yyyy-MM-dd"); }
        int maxDays = Math.max(1, Math.min(366, config.getMaxDays()));
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start,end) >= maxDays) throw new IllegalArgumentException("日期范围需在1至" + maxDays + "天内");
        String uploaded = request.uploaded == null ? "" : request.uploaded.trim();
        if (!Arrays.asList("","0","1").contains(uploaded)) throw new IllegalArgumentException("上传标识只能为空、0或1");
        String keyword = request.keyword == null ? "" : request.keyword.trim();
        if (keyword.length() > 100) throw new IllegalArgumentException("关键词不能超过100个字符");
        Map<String,Object> params = new HashMap<>();
        params.put("trace_start",Timestamp.valueOf(start.atStartOfDay()));
        params.put("trace_end",Timestamp.valueOf(end.plusDays(1).atStartOfDay()));
        List<String> uploadedValues = new ArrayList<>(); profile.uploadedValues.forEach(v -> uploadedValues.add(v.trim()));
        params.put("trace_values",uploadedValues);
        String flag = "case when trim(cast(q." + profile.statusColumn + " as varchar(128))) in (:trace_values) then 1 else 0 end";
        String where = " where q." + profile.timeColumn + ">=:trace_start and q." + profile.timeColumn + "<:trace_end";
        if (!keyword.isEmpty()) {
            if (profile.keywordColumns.isEmpty()) throw new IllegalArgumentException("此查询方案未配置关键词字段");
            params.put("trace_keyword","%" + keyword.replace("!","!!").replace("%","!%").replace("_","!_") + "%");
            List<String> matches = new ArrayList<>();
            profile.keywordColumns.forEach(column -> matches.add("cast(q." + column + " as varchar(4000)) like :trace_keyword escape '!'"));
            where += " and (" + String.join(" or ",matches) + ")";
        }
        String from = " from (" + profile.sql + ") q";
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(Math.max(1,Math.min(300,config.getQueryTimeoutSeconds())));
        jdbc.setFetchSize(request.pageSize);
        NamedParameterJdbcTemplate template = new NamedParameterJdbcTemplate(jdbc);
        Map<String,Object> counts = template.queryForMap("select count(*) trace_total, coalesce(sum(" + flag + "),0) trace_uploaded" + from + where,params);
        long total = ((Number)counts.get("trace_total")).longValue(), sent = ((Number)counts.get("trace_uploaded")).longValue();
        Map<String,Object> stats = new LinkedHashMap<>(); stats.put("total",total); stats.put("uploaded",sent); stats.put("notUploaded",total-sent);
        long filteredTotal = uploaded.isEmpty() ? total : "1".equals(uploaded) ? sent : total-sent;
        int pages = (int)Math.max(1,(filteredTotal + request.pageSize - 1)/request.pageSize);
        int page = Math.min(request.page,pages);
        if (!uploaded.isEmpty()) { where += " and " + flag + "=:trace_flag"; params.put("trace_flag",Integer.valueOf(uploaded)); }
        String order = "q." + profile.timeColumn + " desc";
        for (String key : profile.keyColumns) if (!key.equalsIgnoreCase(profile.timeColumn)) order += ",q." + key;
        params.put("trace_offset",(long)(page-1)*request.pageSize); params.put("trace_limit",(long)page*request.pageSize);
        List<String> fields = new ArrayList<>(); profile.columns.forEach(column -> fields.add("q." + column.field));
        String sql = "select * from (select " + String.join(",",fields) + "," + flag + " trace_internal_uploaded, row_number() over(order by " + order + ") trace_internal_row" + from + where
                + ") trace_page where trace_internal_row>:trace_offset and trace_internal_row<=:trace_limit order by trace_internal_row";
        List<Map<String,Object>> rows = new ArrayList<>();
        if (filteredTotal > 0) {
            for (Map<String,Object> raw : template.queryForList(sql,params)) {
                Map<String,Object> row = new LinkedHashMap<>(), data = new LinkedHashMap<>();
                profile.columns.forEach(column -> data.put(column.field,display(raw.get(column.field))));
                row.put("id",String.valueOf(raw.get("trace_internal_row"))); row.put("uploaded",((Number)raw.get("trace_internal_uploaded")).intValue()==1); row.put("data",data); rows.add(row);
            }
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("rows",rows); result.put("total",filteredTotal); result.put("stats",stats); result.put("page",page); result.put("pageSize",request.pageSize); return result;
    }
    private Object display(Object value) {
        if (value instanceof Timestamp) return ((Timestamp)value).toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        if (value instanceof java.sql.Date) return ((java.sql.Date)value).toLocalDate().toString();
        if (value instanceof Clob) throw new IllegalArgumentException("列表字段不支持CLOB，请在查询SQL中选取需要的短文本");
        return value;
    }
}
