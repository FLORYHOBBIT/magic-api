package org.ssssssss.magicapi.core.config;

/** External, administrator-owned definitions for read-only upload-flag queries. */
public class TraceUploadQueryConfig {
    private String configFile = "config/trace-upload-query.json";
    private int maxDays = 31;
    private int queryTimeoutSeconds = 30;

    public String getConfigFile() { return configFile; }
    public void setConfigFile(String configFile) { this.configFile = configFile; }
    public int getMaxDays() { return maxDays; }
    public void setMaxDays(int maxDays) { this.maxDays = maxDays; }
    public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
    public void setQueryTimeoutSeconds(int value) { this.queryTimeoutSeconds = value; }
}
