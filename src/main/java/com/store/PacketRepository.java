package com.store;

import com.capture.PacketDirection;
import com.capture.PacketEvent;
import com.capture.PayloadStoreType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PacketRepository {

    private static final Logger log = LoggerFactory.getLogger(PacketRepository.class);

    private final SqliteDatabase sqliteDatabase;

    private final PayloadFileStore payloadFileStore;

    public PacketRepository(SqliteDatabase sqliteDatabase) {
        this(sqliteDatabase, null);
    }

    public PacketRepository(SqliteDatabase sqliteDatabase, PayloadFileStore payloadFileStore) {
        this.sqliteDatabase = sqliteDatabase;
        this.payloadFileStore = payloadFileStore;
    }

    public void insertBatch(List<PacketEvent> events) {
        if (events == null || events.size() == 0) {
            return;
        }
        List<PayloadFileStore.PayloadWriteResult> payloadWrites = writePayloadFiles(events);
        String packetSql = "INSERT INTO packet(mapping_id, connection_id, direction, sequence_no, client_ip, client_port, "
                + "listen_ip, listen_port, target_host, target_port, remote_ip, remote_port, payload, payload_size, "
                + "captured_size, truncated, protocol_family, application_protocol, content_type, http_method, http_uri, http_status, "
                + "payload_store_type, payload_file_path, payload_file_offset, payload_file_length, payload_preview_size, "
                + "payload_complete, payload_sha256, received_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
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
                packetStatement.setBytes(13, emptyToNull(event.getPayloadPreview()));
                packetStatement.setInt(14, event.getPayloadSize());
                packetStatement.setInt(15, event.getCapturedSize());
                packetStatement.setInt(16, event.isTruncated() ? 1 : 0);
                packetStatement.setString(17, event.getProtocolFamily());
                packetStatement.setString(18, event.getApplicationProtocol());
                packetStatement.setString(19, event.getContentType());
                packetStatement.setString(20, event.getHttpMethod());
                packetStatement.setString(21, event.getHttpUri());
                if (event.getHttpStatus() == null) {
                    packetStatement.setObject(22, null);
                } else {
                    packetStatement.setInt(22, event.getHttpStatus());
                }
                packetStatement.setString(23, event.getPayloadStoreType());
                packetStatement.setString(24, event.getPayloadFilePath());
                if (event.getPayloadFileOffset() == null) {
                    packetStatement.setObject(25, null);
                } else {
                    packetStatement.setLong(25, event.getPayloadFileOffset());
                }
                if (event.getPayloadFileLength() == null) {
                    packetStatement.setObject(26, null);
                } else {
                    packetStatement.setInt(26, event.getPayloadFileLength());
                }
                packetStatement.setInt(27, event.getPayloadPreviewSize());
                packetStatement.setInt(28, event.isPayloadComplete() ? 1 : 0);
                packetStatement.setString(29, event.getPayloadSha256());
                packetStatement.setString(30, event.getReceivedAt());
                packetStatement.addBatch();
                addDelta(connectionDeltas, event);
            }
            packetStatement.executeBatch();
            updateConnectionBytes(connection, connectionDeltas);
            connection.commit();
        } catch (SQLException e) {
            rollbackPayloadFiles(payloadWrites);
            throw new RuntimeException("insert packet batch failed", e);
        }
    }

    public PageResult<PacketRecord> query(PacketQuery query) {
        PacketQuery normalized = normalize(query);
        QueryParts queryParts = buildQueryParts(normalized);
        long total = count(queryParts);
        String sql = selectSummaryColumns()
                + " FROM packet"
                + queryParts.whereSql
                + " ORDER BY received_at DESC, id DESC LIMIT ? OFFSET ?";
        List<PacketRecord> items = new ArrayList<>();
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindParams(statement, queryParts.params, 1);
            statement.setInt(index++, normalized.pageSize);
            statement.setInt(index, (normalized.page - 1) * normalized.pageSize);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    items.add(toRecord(resultSet, false));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("query packets failed", e);
        }
        return new PageResult<>(items, normalized.page, normalized.pageSize, total);
    }

    public PacketRecord findById(long id) {
        String sql = selectDetailColumns() + " FROM packet WHERE id = ?";
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return toRecord(resultSet, true);
                }
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("query packet failed", e);
        }
    }

    public List<PacketRecord> findRecentByConnectionId(long connectionId, int limit) {
        int effectiveLimit = limit < 1 ? 1 : Math.min(limit, 200);
        String sql = selectSummaryColumns()
                + " FROM packet WHERE connection_id = ? ORDER BY received_at DESC, id DESC LIMIT ?";
        List<PacketRecord> items = new ArrayList<>();
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, connectionId);
            statement.setInt(2, effectiveLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    items.add(toRecord(resultSet, false));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("query recent packets failed", e);
        }
        return items;
    }

    public byte[] readPayload(PacketRecord record) {
        if (record == null) {
            return new byte[0];
        }
        PayloadFileStore.PayloadReadHandle readHandle = openPayloadReadHandle(record);
        if (readHandle != null) {
            try {
                return payloadFileStore.read(record.payloadFilePath, record.payloadFileOffset, record.payloadFileLength);
            } catch (RuntimeException e) {
                log.warn("read payload file failed for packet {} cause:{}", record.id, e.getMessage());
            }
        }
        return record.payload == null ? new byte[0] : record.payload;
    }

    public boolean hasPayloadFile(PacketRecord record) {
        return record != null
                && record.payloadStoreType == PayloadStoreType.FILE
                && payloadFileStore != null
                && payloadFileStore.exists(record.payloadFilePath);
    }

    public PayloadFileStore.PayloadReadHandle openPayloadReadHandle(PacketRecord record) {
        if (record == null
                || record.payloadStoreType != PayloadStoreType.FILE
                || payloadFileStore == null
                || record.payloadFilePath == null
                || record.payloadFileOffset == null
                || record.payloadFileLength == null) {
            return null;
        }
        try {
            return payloadFileStore.openReadHandle(record.payloadFilePath, record.payloadFileOffset, record.payloadFileLength);
        } catch (RuntimeException e) {
            log.warn("open payload file failed for packet {} cause:{}", record.id, e.getMessage());
            return null;
        }
    }

    public void markPayloadFilesDeleted(List<String> relativePaths) {
        if (relativePaths == null || relativePaths.isEmpty()) {
            return;
        }
        String sql = "UPDATE packet SET payload_store_type = ?, payload_complete = 0 WHERE payload_file_path = ? AND payload_store_type = ?";
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String relativePath : relativePaths) {
                if (relativePath == null || relativePath.trim().length() == 0) {
                    continue;
                }
                statement.setString(1, PayloadStoreType.FILE_DELETED.name());
                statement.setString(2, relativePath);
                statement.setString(3, PayloadStoreType.FILE.name());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("mark payload files deleted failed", e);
        }
    }

    private List<PayloadFileStore.PayloadWriteResult> writePayloadFiles(List<PacketEvent> events) {
        List<PayloadFileStore.PayloadWriteResult> writes = new ArrayList<>();
        for (PacketEvent event : events) {
            normalizePayloadMetadata(event);
            if (payloadFileStore == null || PayloadStoreType.fromConfig(event.getPayloadStoreType()) != PayloadStoreType.FILE) {
                continue;
            }
            try {
                PayloadFileStore.PayloadWriteResult result = payloadFileStore.write(
                        event.getMappingId(),
                        event.getReceivedAt(),
                        event.getPayload(),
                        event.isPayloadComplete());
                writes.add(result);
                event.setPayloadFilePath(result.getRelativePath());
                event.setPayloadFileOffset(result.getOffset());
                event.setPayloadFileLength(result.getLength());
                event.setPayloadSha256(result.getSha256());
            } catch (RuntimeException e) {
                log.warn("write payload file failed for connection {} seq {} cause:{}",
                        event.getConnectionId(), event.getSequenceNo(), e.getMessage());
                event.setPayloadStoreType(PayloadStoreType.PREVIEW_ONLY.name());
                event.setPayloadFilePath(null);
                event.setPayloadFileOffset(null);
                event.setPayloadFileLength(null);
                event.setPayloadSha256(null);
                event.setPayloadComplete(false);
            }
        }
        return writes;
    }

    private void rollbackPayloadFiles(List<PayloadFileStore.PayloadWriteResult> payloadWrites) {
        if (payloadFileStore == null || payloadWrites == null || payloadWrites.isEmpty()) {
            return;
        }
        try {
            payloadFileStore.rollback(payloadWrites);
        } catch (RuntimeException rollbackError) {
            log.warn("rollback orphan payload files failed cause:{}", rollbackError.getMessage());
        }
    }

    private void normalizePayloadMetadata(PacketEvent event) {
        if (event.getPayloadPreview() == null) {
            event.setPayloadPreview(new byte[0]);
        }
        if (event.getPayload() == null) {
            event.setPayload(new byte[0]);
        }
        if (event.getPayloadStoreType() == null || event.getPayloadStoreType().trim().length() == 0) {
            event.setPayloadStoreType(PayloadStoreType.PREVIEW_ONLY.name());
        }
        if (event.getPayloadPreviewSize() <= 0 && event.getPayloadPreview() != null) {
            event.setPayloadPreviewSize(event.getPayloadPreview().length);
        }
        if (PayloadStoreType.fromConfig(event.getPayloadStoreType()) != PayloadStoreType.FILE) {
            event.setPayloadStoreType(PayloadStoreType.PREVIEW_ONLY.name());
            event.setPayloadFilePath(null);
            event.setPayloadFileOffset(null);
            event.setPayloadFileLength(null);
            event.setPayloadSha256(null);
        }
    }

    private byte[] emptyToNull(byte[] bytes) {
        return bytes == null || bytes.length == 0 ? null : bytes;
    }

    private long count(QueryParts queryParts) {
        String sql = "SELECT COUNT(*) FROM packet" + queryParts.whereSql;
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParams(statement, queryParts.params, 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("count packets failed", e);
        }
    }

    private QueryParts buildQueryParts(PacketQuery query) {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        appendClause(where, params, query.mappingId, "mapping_id = ?");
        appendClause(where, params, query.connectionId, "connection_id = ?");
        appendClause(where, params, query.direction, "direction = ?");
        appendClause(where, params, query.from, "received_at >= ?");
        appendClause(where, params, query.to, "received_at <= ?");
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

    private PacketQuery normalize(PacketQuery query) {
        PacketQuery normalized = query == null ? new PacketQuery() : query;
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

    private String selectSummaryColumns() {
        return "SELECT id, mapping_id, connection_id, direction, sequence_no, client_ip, client_port, "
                + "listen_ip, listen_port, target_host, target_port, remote_ip, remote_port, "
                + "payload_size, captured_size, truncated, protocol_family, application_protocol, content_type, "
                + "http_method, http_uri, http_status, payload_store_type, payload_file_path, payload_file_offset, "
                + "payload_file_length, payload_preview_size, payload_complete, payload_sha256, received_at";
    }

    private String selectDetailColumns() {
        return "SELECT id, mapping_id, connection_id, direction, sequence_no, client_ip, client_port, "
                + "listen_ip, listen_port, target_host, target_port, remote_ip, remote_port, "
                + "payload, payload_size, captured_size, truncated, protocol_family, application_protocol, content_type, "
                + "http_method, http_uri, http_status, payload_store_type, payload_file_path, payload_file_offset, "
                + "payload_file_length, payload_preview_size, payload_complete, payload_sha256, received_at";
    }

    private PacketRecord toRecord(ResultSet resultSet, boolean includePayload) throws SQLException {
        PacketRecord record = new PacketRecord();
        record.id = resultSet.getLong("id");
        record.mappingId = resultSet.getLong("mapping_id");
        record.connectionId = resultSet.getLong("connection_id");
        record.direction = resultSet.getString("direction");
        record.sequenceNo = resultSet.getLong("sequence_no");
        record.clientIp = resultSet.getString("client_ip");
        record.clientPort = resultSet.getInt("client_port");
        record.listenIp = resultSet.getString("listen_ip");
        record.listenPort = resultSet.getInt("listen_port");
        record.targetHost = resultSet.getString("target_host");
        record.targetPort = resultSet.getInt("target_port");
        record.remoteIp = resultSet.getString("remote_ip");
        record.remotePort = resultSet.getInt("remote_port");
        record.payloadSize = resultSet.getInt("payload_size");
        record.capturedSize = resultSet.getInt("captured_size");
        record.truncated = resultSet.getInt("truncated") == 1;
        record.protocolFamily = resultSet.getString("protocol_family");
        record.applicationProtocol = resultSet.getString("application_protocol");
        record.contentType = resultSet.getString("content_type");
        record.httpMethod = resultSet.getString("http_method");
        record.httpUri = resultSet.getString("http_uri");
        int httpStatus = resultSet.getInt("http_status");
        record.httpStatus = resultSet.wasNull() ? null : httpStatus;
        record.payloadStoreType = PayloadStoreType.fromConfig(resultSet.getString("payload_store_type"));
        record.payloadFilePath = resultSet.getString("payload_file_path");
        long payloadFileOffset = resultSet.getLong("payload_file_offset");
        record.payloadFileOffset = resultSet.wasNull() ? null : payloadFileOffset;
        int payloadFileLength = resultSet.getInt("payload_file_length");
        record.payloadFileLength = resultSet.wasNull() ? null : payloadFileLength;
        record.payloadPreviewSize = resultSet.getInt("payload_preview_size");
        record.payloadComplete = resultSet.getInt("payload_complete") == 1;
        record.payloadSha256 = resultSet.getString("payload_sha256");
        record.receivedAt = resultSet.getString("received_at");
        if (includePayload) {
            byte[] payload = resultSet.getBytes("payload");
            record.payload = payload == null ? new byte[0] : payload;
        }
        return record;
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

    private static class QueryParts {

        private final String whereSql;

        private final List<Object> params;

        private QueryParts(String whereSql, List<Object> params) {
            this.whereSql = whereSql;
            this.params = params;
        }
    }

    public static class PacketQuery {

        public Long mappingId;

        public Long connectionId;

        public String direction;

        public String from;

        public String to;

        public int page = 1;

        public int pageSize = 50;
    }

    public static class PacketRecord {

        public long id;

        public long mappingId;

        public long connectionId;

        public String direction;

        public long sequenceNo;

        public String clientIp;

        public int clientPort;

        public String listenIp;

        public int listenPort;

        public String targetHost;

        public int targetPort;

        public String remoteIp;

        public int remotePort;

        public int payloadSize;

        public int capturedSize;

        public boolean truncated;

        public String receivedAt;

        public byte[] payload;

        public String protocolFamily;

        public String applicationProtocol;

        public String contentType;

        public String httpMethod;

        public String httpUri;

        public Integer httpStatus;

        public PayloadStoreType payloadStoreType;

        public String payloadFilePath;

        public Long payloadFileOffset;

        public Integer payloadFileLength;

        public int payloadPreviewSize;

        public boolean payloadComplete;

        public String payloadSha256;
    }
}
