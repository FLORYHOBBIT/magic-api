package org.ssssssss.magicapi.core.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ssssssss.magicapi.core.config.TraceUploadQueryConfig;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Loads only an administrator-selected file, never SQL/identifiers supplied by a query request. */
public final class TraceQueryProfiles {
    private final TraceUploadQueryConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    public TraceQueryProfiles(TraceUploadQueryConfig config) { this.config = config; }

    public List<Profile> load() throws IOException {
        if (config == null || config.getConfigFile() == null || config.getConfigFile().trim().isEmpty()) return Collections.emptyList();
        Path file = Paths.get(config.getConfigFile()).toAbsolutePath().normalize();
        if (!Files.exists(file)) return Collections.emptyList();
        if (!Files.isRegularFile(file) || Files.size(file) > 2 * 1024 * 1024) throw new IllegalArgumentException("上传查询配置文件无效或超过2MB");
        Definitions definitions = mapper.readValue(Files.readAllBytes(file), Definitions.class);
        if (definitions.version != 1 || definitions.profiles == null || definitions.profiles.size() > 50) throw new IllegalArgumentException("上传查询配置版本或方案数量无效");
        Set<String> ids = new HashSet<>();
        for (Profile profile : definitions.profiles) {
            validate(profile);
            if (!ids.add(profile.id)) throw new IllegalArgumentException("上传查询方案ID重复：" + profile.id);
        }
        return definitions.profiles;
    }

    public static void validate(Profile p) {
        if (p == null || p.id == null || !p.id.matches("[A-Za-z0-9_-]{1,64}") || p.name == null || p.name.trim().isEmpty()
                || p.dataSource == null || p.dataSource.trim().isEmpty()) throw new IllegalArgumentException("上传查询方案缺少ID、名称或数据源");
        identifier(p.timeColumn); identifier(p.statusColumn);
        if (p.uploadedValues == null || p.uploadedValues.isEmpty() || p.uploadedValues.size() > 20
                || p.uploadedValues.stream().anyMatch(v -> v == null || v.trim().isEmpty() || v.length() > 100)) throw new IllegalArgumentException("已上传值配置无效");
        if (p.columns == null || p.columns.isEmpty() || p.columns.size() > 30) throw new IllegalArgumentException("需配置1至30个显示字段");
        Set<String> fields = new HashSet<>();
        for (Column column : p.columns) {
            if (column == null) throw new IllegalArgumentException("显示字段不能为空");
            identifier(column.field);
            if (!fields.add(column.field.toLowerCase(Locale.ROOT)) || column.label == null || column.label.trim().isEmpty()) throw new IllegalArgumentException("显示字段重复或缺少标题");
            if (!Arrays.asList("text","date","datetime","number").contains(column.type)) throw new IllegalArgumentException("显示字段类型无效");
            if (column.width < 60 || column.width > 600) throw new IllegalArgumentException("字段宽度需在60至600之间");
        }
        if (p.keywordColumns == null || p.keywordColumns.size() > 20 || p.keyColumns == null || p.keyColumns.size() > 10 || p.resourcePaths == null) throw new IllegalArgumentException("查询字段配置无效");
        p.keywordColumns.forEach(TraceQueryProfiles::identifier); p.keyColumns.forEach(TraceQueryProfiles::identifier);
        if (p.resourcePaths.stream().anyMatch(path -> path == null || !path.startsWith("/"))) throw new IllegalArgumentException("绑定接口路径必须为完整路径");
        if (p.sql == null || p.sql.length() > 100000) throw new IllegalArgumentException("查询SQL缺失或过长");
        String tokens = p.sql.replaceAll("'(''|[^'])*'", "''").replaceAll("\"(\"\"|[^\"])*\"", "identifier")
                .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\r\\n]*", " ").trim().toLowerCase(Locale.ROOT);
        if (!tokens.matches("(?s)^(select|with)\\b.*") || tokens.contains(";")
                || tokens.matches("(?s).*\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|call|execute|begin|commit|rollback)\\b.*"))
            throw new IllegalArgumentException("查询方案只允许单条SELECT/WITH查询，不允许写入或锁定语句");
    }
    static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || value.toLowerCase(Locale.ROOT).startsWith("trace_internal_")) throw new IllegalArgumentException("查询字段名无效：" + value);
    }
    public static class Definitions { public int version = 1; public List<Profile> profiles = new ArrayList<>(); }
    public static class Profile {
        public String id, name, dataSource, sql, timeColumn, statusColumn;
        public List<String> uploadedValues = new ArrayList<>();
        public List<String> keywordColumns = new ArrayList<>(), keyColumns = new ArrayList<>(), resourcePaths = new ArrayList<>();
        public List<Column> columns = new ArrayList<>();
        public Map<String,Object> metadata() {
            Map<String,Object> meta = new LinkedHashMap<>(); meta.put("id",id); meta.put("name",name); meta.put("columns",columns); return meta;
        }
    }
    public static class Column {
        public String field, label;
        public String type = "text";
        public int width = 150;
        public boolean visible = true;
    }
}
