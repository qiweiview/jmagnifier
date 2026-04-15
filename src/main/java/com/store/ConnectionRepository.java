package com.store;

import com.model.Mapping;
import io.netty.channel.Channel;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.sql.*;
import java.time.Instant;

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
}
