package com.store;

import com.model.Mapping;
import io.netty.channel.Channel;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ConnectionRepository {

    private final SqliteDatabase sqliteDatabase;

    public ConnectionRepository(SqliteDatabase sqliteDatabase) {
        this.sqliteDatabase = sqliteDatabase;
    }

    public long openConnection(long mappingId, Mapping mapping, Channel localChannel) {
        InetSocketAddress clientAddress = toInetSocketAddress(localChannel.remoteAddress());
        InetSocketAddress listenAddress = toInetSocketAddress(localChannel.localAddress());
        String sql = "INSERT INTO connection(mapping_id, client_ip, client_port, listen_ip, listen_port, "
                + "forward_host, forward_port, status, opened_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, mappingId);
            statement.setString(2, host(clientAddress));
            statement.setInt(3, port(clientAddress));
            statement.setString(4, host(listenAddress));
            statement.setInt(5, port(listenAddress));
            statement.setString(6, mapping.getForwardHost());
            statement.setInt(7, mapping.getForwardPort());
            statement.setString(8, "OPENING");
            statement.setString(9, Instant.now().toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            throw new RuntimeException("insert connection did not return id");
        } catch (SQLException e) {
            throw new RuntimeException("open connection failed", e);
        }
    }

    public void markOpen(long connectionId, Channel remoteChannel) {
        InetSocketAddress remoteAddress = remoteChannel == null ? null : toInetSocketAddress(remoteChannel.remoteAddress());
        String sql = "UPDATE connection SET status = ?, remote_ip = ?, remote_port = ? WHERE id = ?";
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "OPEN");
            statement.setString(2, host(remoteAddress));
            statement.setInt(3, port(remoteAddress));
            statement.setLong(4, connectionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("mark connection open failed", e);
        }
    }

    public void closeConnection(long connectionId, String status, String closeReason, String errorMessage) {
        String sql = "UPDATE connection SET status = ?, close_reason = ?, closed_at = ?, error_message = ? "
                + "WHERE id = ? AND closed_at IS NULL";
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, closeReason);
            statement.setString(3, Instant.now().toString());
            statement.setString(4, errorMessage);
            statement.setLong(5, connectionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("close connection failed", e);
        }
    }

    public PageResult<ConnectionRecord> query(ConnectionQuery query) {
        ConnectionQuery normalized = normalize(query);
        QueryParts queryParts = buildQueryParts(normalized);
        long total = count(queryParts);
        String sql = selectColumns()
                + " FROM connection"
                + queryParts.whereSql
                + " ORDER BY opened_at DESC, id DESC LIMIT ? OFFSET ?";
        List<ConnectionRecord> items = new ArrayList<>();
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindParams(statement, queryParts.params, 1);
            statement.setInt(index++, normalized.pageSize);
            statement.setInt(index, (normalized.page - 1) * normalized.pageSize);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    items.add(toRecord(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("query connections failed", e);
        }
        return new PageResult<>(items, normalized.page, normalized.pageSize, total);
    }

    public ConnectionRecord findById(long id) {
        String sql = selectColumns() + " FROM connection WHERE id = ?";
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return toRecord(resultSet);
                }
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("query connection failed", e);
        }
    }

    private long count(QueryParts queryParts) {
        String sql = "SELECT COUNT(*) FROM connection" + queryParts.whereSql;
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParams(statement, queryParts.params, 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("count connections failed", e);
        }
    }

    private QueryParts buildQueryParts(ConnectionQuery query) {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        appendClause(where, params, query.mappingId, "mapping_id = ?");
        appendClause(where, params, query.clientIp, "client_ip = ?");
        appendClause(where, params, query.status, "status = ?");
        appendClause(where, params, query.from, "opened_at >= ?");
        appendClause(where, params, query.to, "opened_at <= ?");
        return new QueryParts(where.length() == 0 ? "" : " WHERE " + where, params);
    }

    private void appendClause(StringBuilder where, List<Object> params, Object value, String clause) {
        if (value == null) {
            return;
        }
        if (value instanceof String && ((String) value).trim().length() == 0) {
            return;
        }
        if (where.length() > 0) {
            where.append(" AND ");
        }
        where.append(clause);
        params.add(value);
    }

    private int bindParams(PreparedStatement statement, List<Object> params, int startIndex) throws SQLException {
        int index = startIndex;
        for (Object param : params) {
            if (param instanceof Integer) {
                statement.setInt(index++, (Integer) param);
            } else if (param instanceof Long) {
                statement.setLong(index++, (Long) param);
            } else {
                statement.setString(index++, String.valueOf(param));
            }
        }
        return index;
    }

    private ConnectionQuery normalize(ConnectionQuery query) {
        ConnectionQuery normalized = query == null ? new ConnectionQuery() : query;
        if (normalized.page < 1) {
            normalized.page = 1;
        }
        if (normalized.pageSize < 1) {
            normalized.pageSize = 50;
        }
        if (normalized.pageSize > 500) {
            normalized.pageSize = 500;
        }
        return normalized;
    }

    private String selectColumns() {
        return "SELECT id, mapping_id, client_ip, client_port, listen_ip, listen_port, "
                + "forward_host, forward_port, remote_ip, remote_port, status, close_reason, "
                + "opened_at, closed_at, bytes_up, bytes_down, error_message";
    }

    private ConnectionRecord toRecord(ResultSet resultSet) throws SQLException {
        ConnectionRecord record = new ConnectionRecord();
        record.id = resultSet.getLong("id");
        record.mappingId = resultSet.getLong("mapping_id");
        record.clientIp = resultSet.getString("client_ip");
        record.clientPort = resultSet.getInt("client_port");
        record.listenIp = resultSet.getString("listen_ip");
        record.listenPort = resultSet.getInt("listen_port");
        record.forwardHost = resultSet.getString("forward_host");
        record.forwardPort = resultSet.getInt("forward_port");
        record.remoteIp = resultSet.getString("remote_ip");
        record.remotePort = resultSet.getInt("remote_port");
        record.status = resultSet.getString("status");
        record.closeReason = resultSet.getString("close_reason");
        record.openedAt = resultSet.getString("opened_at");
        record.closedAt = resultSet.getString("closed_at");
        record.bytesUp = resultSet.getLong("bytes_up");
        record.bytesDown = resultSet.getLong("bytes_down");
        record.errorMessage = resultSet.getString("error_message");
        return record;
    }

    private InetSocketAddress toInetSocketAddress(SocketAddress socketAddress) {
        if (socketAddress instanceof InetSocketAddress) {
            return (InetSocketAddress) socketAddress;
        }
        return null;
    }

    private String host(InetSocketAddress socketAddress) {
        if (socketAddress == null) {
            return null;
        }
        return socketAddress.getAddress() == null ? socketAddress.getHostString() : socketAddress.getAddress().getHostAddress();
    }

    private int port(InetSocketAddress socketAddress) {
        return socketAddress == null ? -1 : socketAddress.getPort();
    }

    private static class QueryParts {

        private final String whereSql;

        private final List<Object> params;

        private QueryParts(String whereSql, List<Object> params) {
            this.whereSql = whereSql;
            this.params = params;
        }
    }

    public static class ConnectionQuery {

        public Long mappingId;

        public String clientIp;

        public String status;

        public String from;

        public String to;

        public int page = 1;

        public int pageSize = 50;
    }

    public static class ConnectionRecord {

        public long id;

        public long mappingId;

        public String clientIp;

        public int clientPort;

        public String listenIp;

        public int listenPort;

        public String forwardHost;

        public int forwardPort;

        public String remoteIp;

        public int remotePort;

        public String status;

        public String closeReason;

        public String openedAt;

        public String closedAt;

        public long bytesUp;

        public long bytesDown;

        public String errorMessage;
    }
}
