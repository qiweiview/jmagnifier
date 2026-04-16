package com.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseInitializer {

    private final SqliteDatabase sqliteDatabase;

    public DatabaseInitializer(SqliteDatabase sqliteDatabase) {
        this.sqliteDatabase = sqliteDatabase;
    }

    public void initialize() {
        sqliteDatabase.ensureParentDirectory();
        try (Connection connection = sqliteDatabase.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS mapping ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL,"
                    + "enabled INTEGER NOT NULL DEFAULT 1,"
                    + "listen_port INTEGER NOT NULL,"
                    + "forward_host TEXT NOT NULL,"
                    + "forward_port INTEGER NOT NULL,"
                    + "config_json TEXT,"
                    + "deleted INTEGER NOT NULL DEFAULT 0,"
                    + "created_at TEXT NOT NULL,"
                    + "updated_at TEXT NOT NULL"
                    + ")");
            ensureColumn(connection, "mapping", "config_json", "TEXT");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_mapping_deleted ON mapping(deleted)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_mapping_listen_port ON mapping(listen_port)");
            statement.execute("CREATE TABLE IF NOT EXISTS connection ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "mapping_id INTEGER NOT NULL,"
                    + "client_ip TEXT,"
                    + "client_port INTEGER,"
                    + "listen_ip TEXT,"
                    + "listen_port INTEGER,"
                    + "forward_host TEXT,"
                    + "forward_port INTEGER,"
                    + "remote_ip TEXT,"
                    + "remote_port INTEGER,"
                    + "status TEXT NOT NULL,"
                    + "close_reason TEXT,"
                    + "opened_at TEXT NOT NULL,"
                    + "closed_at TEXT,"
                    + "bytes_up INTEGER NOT NULL DEFAULT 0,"
                    + "bytes_down INTEGER NOT NULL DEFAULT 0,"
                    + "error_message TEXT"
                    + ")");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_connection_mapping_time ON connection(mapping_id, opened_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_connection_status ON connection(status)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_connection_client ON connection(client_ip, client_port)");
            statement.execute("CREATE TABLE IF NOT EXISTS packet ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "mapping_id INTEGER NOT NULL,"
                    + "connection_id INTEGER NOT NULL,"
                    + "direction TEXT NOT NULL,"
                    + "sequence_no INTEGER NOT NULL,"
                    + "client_ip TEXT,"
                    + "client_port INTEGER,"
                    + "listen_ip TEXT,"
                    + "listen_port INTEGER,"
                    + "target_host TEXT,"
                    + "target_port INTEGER,"
                    + "remote_ip TEXT,"
                    + "remote_port INTEGER,"
                    + "payload BLOB,"
                    + "payload_size INTEGER NOT NULL,"
                    + "captured_size INTEGER NOT NULL,"
                    + "truncated INTEGER NOT NULL,"
                    + "protocol_family TEXT,"
                    + "application_protocol TEXT,"
                    + "content_type TEXT,"
                    + "http_method TEXT,"
                    + "http_uri TEXT,"
                    + "http_status INTEGER,"
                    + "received_at TEXT NOT NULL"
                    + ")");
            ensureColumn(connection, "packet", "protocol_family", "TEXT");
            ensureColumn(connection, "packet", "application_protocol", "TEXT");
            ensureColumn(connection, "packet", "content_type", "TEXT");
            ensureColumn(connection, "packet", "http_method", "TEXT");
            ensureColumn(connection, "packet", "http_uri", "TEXT");
            ensureColumn(connection, "packet", "http_status", "INTEGER");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_packet_mapping_time ON packet(mapping_id, received_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_packet_connection_seq ON packet(connection_id, sequence_no)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_packet_direction_time ON packet(direction, received_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS setting ("
                    + "key TEXT PRIMARY KEY,"
                    + "value TEXT NOT NULL,"
                    + "updated_at TEXT NOT NULL"
                    + ")");
        } catch (SQLException e) {
            throw new RuntimeException("initialize sqlite schema failed", e);
        }
    }

    private void ensureColumn(Connection connection, String tableName, String columnName, String columnDefinition) throws SQLException {
        if (hasColumn(connection, tableName, columnName)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
        }
    }

    private boolean hasColumn(Connection connection, String tableName, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
