package com.store;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class SqliteDatabase {

    private final String sqlitePath;

    private final String jdbcUrl;

    public SqliteDatabase(String sqlitePath) {
        this.sqlitePath = sqlitePath;
        this.jdbcUrl = "jdbc:sqlite:" + sqlitePath;
    }

    public String getSqlitePath() {
        return sqlitePath;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void ensureParentDirectory() {
        File file = new File(sqlitePath);
        File parentFile = file.getAbsoluteFile().getParentFile();
        if (parentFile != null && !parentFile.exists()) {
            parentFile.mkdirs();
        }
    }

    public Connection getConnection() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }
}
