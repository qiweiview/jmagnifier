package com.store;

import com.capture.PacketDirection;
import com.capture.PacketEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PacketRepository {

    private final SqliteDatabase sqliteDatabase;

    public PacketRepository(SqliteDatabase sqliteDatabase) {
        this.sqliteDatabase = sqliteDatabase;
    }

    public void insertBatch(List<PacketEvent> events) {
        if (events == null || events.size() == 0) {
            return;
        }
        String packetSql = "INSERT INTO packet(mapping_id, connection_id, direction, sequence_no, client_ip, client_port, "
                + "listen_ip, listen_port, target_host, target_port, remote_ip, remote_port, payload, payload_size, "
                + "captured_size, truncated, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement packetStatement = connection.prepareStatement(packetSql)) {
            connection.setAutoCommit(false);
            Map<Long, long[]> connectionDeltas = new HashMap<>();
            for (PacketEvent event : events) {
                packetStatement.setLong(1, event.getMappingId());
                packetStatement.setLong(2, event.getConnectionId());
                packetStatement.setString(3, event.getDirection().name());
                packetStatement.setLong(4, event.getSequenceNo());
                packetStatement.setString(5, event.getClientIp());
                packetStatement.setInt(6, event.getClientPort());
                packetStatement.setString(7, event.getListenIp());
                packetStatement.setInt(8, event.getListenPort());
                packetStatement.setString(9, event.getTargetHost());
                packetStatement.setInt(10, event.getTargetPort());
                packetStatement.setString(11, event.getRemoteIp());
                packetStatement.setInt(12, event.getRemotePort());
                packetStatement.setBytes(13, event.getPayload());
                packetStatement.setInt(14, event.getPayloadSize());
                packetStatement.setInt(15, event.getCapturedSize());
                packetStatement.setInt(16, event.isTruncated() ? 1 : 0);
                packetStatement.setString(17, event.getReceivedAt());
                packetStatement.addBatch();
                addDelta(connectionDeltas, event);
            }
            packetStatement.executeBatch();
            updateConnectionBytes(connection, connectionDeltas);
            connection.commit();
        } catch (SQLException e) {
            throw new RuntimeException("insert packet batch failed", e);
        }
    }

    private void addDelta(Map<Long, long[]> connectionDeltas, PacketEvent event) {
        if (event.getConnectionId() <= 0) {
            return;
        }
        long[] delta = connectionDeltas.computeIfAbsent(event.getConnectionId(), key -> new long[]{0, 0});
        if (event.getDirection() == PacketDirection.REQUEST) {
            delta[0] += event.getPayloadSize();
        } else if (event.getDirection() == PacketDirection.RESPONSE) {
            delta[1] += event.getPayloadSize();
        }
    }

    private void updateConnectionBytes(Connection connection, Map<Long, long[]> connectionDeltas) throws SQLException {
        if (connectionDeltas.size() == 0) {
            return;
        }
        String sql = "UPDATE connection SET bytes_up = bytes_up + ?, bytes_down = bytes_down + ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<Long, long[]> entry : connectionDeltas.entrySet()) {
                statement.setLong(1, entry.getValue()[0]);
                statement.setLong(2, entry.getValue()[1]);
                statement.setLong(3, entry.getKey());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
