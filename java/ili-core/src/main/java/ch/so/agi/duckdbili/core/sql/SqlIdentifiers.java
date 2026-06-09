package ch.so.agi.duckdbili.core.sql;

import java.text.Normalizer;

public final class SqlIdentifiers {
    private SqlIdentifiers() {}

    public static String quoteIdent(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    public static String sqlString(String s) {
        if (s == null) return "NULL";
        return "'" + s.replace("'", "''") + "'";
    }

    public static String collisionKey(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase();
    }

    public static String sanitizeTableName(String s) {
        if (s == null) return "";
        return collisionKey(s).replaceAll("[^a-z0-9_]", "_");
    }

    public static String normalizeColumnName(String s) {
        if (s == null) return "";
        return collisionKey(s);
    }
}
