package ch.so.agi.duckdbili.core.validation;

import java.util.ArrayList;
import java.util.List;

public class ValidationMessage {
    private final String severity;
    private final String code;
    private final String message;
    private final String fileName;
    private final Integer line;
    private final Integer column;
    private final String xtfTid;
    private final String xtfBid;
    private final String model;
    private final String topic;
    private final String className;
    private final String attributeName;
    private final String raw;

    private ValidationMessage(Builder builder) {
        this.severity = builder.severity;
        this.code = builder.code;
        this.message = builder.message;
        this.fileName = builder.fileName;
        this.line = builder.line;
        this.column = builder.column;
        this.xtfTid = builder.xtfTid;
        this.xtfBid = builder.xtfBid;
        this.model = builder.model;
        this.topic = builder.topic;
        this.className = builder.className;
        this.attributeName = builder.attributeName;
        this.raw = builder.raw;
    }

    public String getSeverity() { return severity; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getFileName() { return fileName; }
    public Integer getLine() { return line; }
    public Integer getColumn() { return column; }
    public String getXtfTid() { return xtfTid; }
    public String getXtfBid() { return xtfBid; }
    public String getModel() { return model; }
    public String getTopic() { return topic; }
    public String getClassName() { return className; }
    public String getAttributeName() { return attributeName; }
    public String getRaw() { return raw; }

    public static class Builder {
        private String severity;
        private String code;
        private String message;
        private String fileName;
        private Integer line;
        private Integer column;
        private String xtfTid;
        private String xtfBid;
        private String model;
        private String topic;
        private String className;
        private String attributeName;
        private String raw;

        public Builder severity(String v) { severity = v; return this; }
        public Builder code(String v) { code = v; return this; }
        public Builder message(String v) { message = v; return this; }
        public Builder fileName(String v) { fileName = v; return this; }
        public Builder line(Integer v) { line = v; return this; }
        public Builder column(Integer v) { column = v; return this; }
        public Builder xtfTid(String v) { xtfTid = v; return this; }
        public Builder xtfBid(String v) { xtfBid = v; return this; }
        public Builder model(String v) { model = v; return this; }
        public Builder topic(String v) { topic = v; return this; }
        public Builder className(String v) { className = v; return this; }
        public Builder attributeName(String v) { attributeName = v; return this; }
        public Builder raw(String v) { raw = v; return this; }

        public ValidationMessage build() {
            return new ValidationMessage(this);
        }
    }
}
