package org.ssssssss.magicapi.upload.channel.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ssssssss.magicapi.upload.channel.BodyBuilder;
import org.ssssssss.magicapi.upload.engine.UploadBatch;
import org.ssssssss.magicapi.upload.store.PayloadCodec;

import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 文件类通道（本地目录、FTP、SFTP）的公共配置：内容格式、文件名、编码
 */
public class FileChannelConfig {

	public static final String FORMAT_JSON = "json";

	public static final String FORMAT_CSV = "csv";

	private static final Pattern SAFE_NAME = Pattern.compile("[^\\\\/:*?\"<>|\\r\\n]+");

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private String format = FORMAT_JSON;

	/**
	 * 可用变量：{job}、{batch}（批次序号，补零到 4 位）、{date}（yyyyMMdd）、{datetime}（yyyyMMddHHmmss）、{ext}
	 */
	private String fileName = "{job}_{datetime}_{batch}.{ext}";

	private Charset encoding = Charset.forName("UTF-8");

	private boolean csvHeader = true;

	private BodyBuilder bodyBuilder;

	public String getFormat() {
		return format;
	}

	public FileChannelConfig format(String format) {
		String value = format == null ? FORMAT_JSON : format.trim().toLowerCase(Locale.ROOT);
		if (!FORMAT_JSON.equals(value) && !FORMAT_CSV.equals(value)) {
			throw new IllegalArgumentException("文件格式只支持 json、csv: " + format);
		}
		this.format = value;
		return this;
	}

	public String getFileName() {
		return fileName;
	}

	public FileChannelConfig fileName(String fileName) {
		if (fileName != null && !fileName.trim().isEmpty()) {
			this.fileName = fileName.trim();
		}
		return this;
	}

	public Charset getEncoding() {
		return encoding;
	}

	/**
	 * 文件内容编码，默认 UTF-8；对方要求 GBK 时设置为 GBK
	 */
	public FileChannelConfig encoding(String encoding) {
		if (encoding != null && !encoding.trim().isEmpty()) {
			this.encoding = Charset.forName(encoding.trim());
		}
		return this;
	}

	public boolean isCsvHeader() {
		return csvHeader;
	}

	public FileChannelConfig csvHeader(boolean csvHeader) {
		this.csvHeader = csvHeader;
		return this;
	}

	public BodyBuilder getBodyBuilder() {
		return bodyBuilder;
	}

	/**
	 * 可选。返回字符串时作为文件内容原样写入；返回列表时按格式输出
	 */
	public FileChannelConfig bodyBuilder(BodyBuilder bodyBuilder) {
		this.bodyBuilder = bodyBuilder;
		return this;
	}

	/**
	 * 生成本批的文件名（只含文件名，不含目录）
	 */
	public String resolveFileName(UploadBatch batch) {
		Date created = new Date(batch.getCreatedAt());
		String name = fileName
				.replace("{job}", batch.getJobName())
				.replace("{batch}", String.format("%04d", batch.getIndex()))
				.replace("{date}", new SimpleDateFormat("yyyyMMdd").format(created))
				.replace("{datetime}", new SimpleDateFormat("yyyyMMddHHmmss").format(created))
				.replace("{ext}", format);
		if (!SAFE_NAME.matcher(name).matches() || name.startsWith(".")) {
			throw new IllegalArgumentException("生成的文件名不合法（不能包含路径分隔符或 \\ / : * ? \" < > |）: " + name);
		}
		return name;
	}

	/**
	 * 生成本批的文件内容
	 */
	public byte[] render(UploadBatch batch) throws Exception {
		Object content = bodyBuilder == null ? batch.getRows() : bodyBuilder.build(batch);
		if (content instanceof CharSequence) {
			return content.toString().getBytes(encoding);
		}
		if (FORMAT_CSV.equals(format)) {
			if (!(content instanceof Collection)) {
				throw new IllegalArgumentException("CSV 格式的内容必须是数据列表");
			}
			return toCsv((Collection<?>) content).getBytes(encoding);
		}
		return MAPPER.writeValueAsString(PayloadCodec.normalize(content)).getBytes(encoding);
	}

	String toCsv(Collection<?> rows) {
		LinkedHashSet<String> columns = new LinkedHashSet<>();
		for (Object row : rows) {
			if (!(row instanceof Map)) {
				throw new IllegalArgumentException("CSV 格式的每一行必须是对象");
			}
			for (Object key : ((Map<?, ?>) row).keySet()) {
				columns.add(String.valueOf(key));
			}
		}
		StringBuilder sb = new StringBuilder();
		if (csvHeader) {
			appendLine(sb, new ArrayList<Object>(columns));
		}
		for (Object row : rows) {
			Map<?, ?> map = (Map<?, ?>) row;
			List<Object> values = new ArrayList<>();
			for (String column : columns) {
				values.add(PayloadCodec.normalize(map.get(column)));
			}
			appendLine(sb, values);
		}
		return sb.toString();
	}

	private static void appendLine(StringBuilder sb, List<Object> values) {
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			Object value = values.get(i);
			String text = value == null ? "" : value.toString();
			if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
				text = "\"" + text.replace("\"", "\"\"") + "\"";
			}
			sb.append(text);
		}
		sb.append("\r\n");
	}
}
