package com.store;

import com.model.Mapping;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class MappingRepository {

    private static final MappingConfigJsonCodec CONFIG_JSON_CODEC = new MappingConfigJsonCodec();

    private final SqliteDatabase sqliteDatabase;

    public MappingRepository(SqliteDatabase sqliteDatabase) {
        this.sqliteDatabase = sqliteDatabase;
    }

    public List<MappingEntity> findAllActive() {
        String sql = "SELECT id, name, enabled, listen_port, forward_host, forward_port, config_json, created_at, updated_at "
                + "FROM mapping WHERE deleted = 0 ORDER BY id ASC";
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<MappingEntity> mappings = new ArrayList<>();
            while (resultSet.next()) {
                mappings.add(toEntity(resultSet));
            }
            return mappings;
        } catch (SQLException e) {
            throw new RuntimeException("query mappings failed", e);
        }
    }

    public long insert(Mapping mapping) {
        mapping.applyDefaults();
        String now = Instant.now().toString();
        String sql = "INSERT INTO mapping(name, enabled, listen_port, forward_host, forward_port, config_json, deleted, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)";
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, mapping.getName());
            statement.setInt(2, Boolean.TRUE.equals(mapping.getEnable()) ? 1 : 0);
            statement.setInt(3, mapping.getListenPort());
            statement.setString(4, mapping.getForwardHost());
            statement.setInt(5, mapping.getForwardPort());
            statement.setString(6, CONFIG_JSON_CODEC.toJson(mapping));
            statement.setString(7, now);
            statement.setString(8, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            throw new RuntimeException("insert mapping did not return id");
        } catch (SQLException e) {
            throw new RuntimeException("insert mapping failed", e);
        }
    }

    public void update(long id, Mapping mapping) {
        mapping.applyDefaults();
        String sql = "UPDATE mapping SET name = ?, enabled = ?, listen_port = ?, forward_host = ?, forward_port = ?, config_json = ?, updated_at = ? "
                + "WHERE id = ? AND deleted = 0";
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, mapping.getName());
            statement.setInt(2, Boolean.TRUE.equals(mapping.getEnable()) ? 1 : 0);
            statement.setInt(3, mapping.getListenPort());
            statement.setString(4, mapping.getForwardHost());
            statement.setInt(5, mapping.getForwardPort());
            statement.setString(6, CONFIG_JSON_CODEC.toJson(mapping));
            statement.setString(7, Instant.now().toString());
            statement.setLong(8, id);
            if (statement.executeUpdate() == 0) {
                throw new RuntimeException("mapping not found: " + id);
            }
        } catch (SQLException e) {
            throw new RuntimeException("update mapping failed", e);
        }
    }

    public void softDelete(long id) {
        String sql = "UPDATE mapping SET deleted = 1, enabled = 0, updated_at = ? WHERE id = ? AND deleted = 0";
        try (Connection connection = sqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Instant.now().toString());
            statement.setLong(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("delete mapping failed", e);
        }
    }

    private MappingEntity toEntity(ResultSet resultSet) throws SQLException {
        MappingEntity entity = new MappingEntity();
        entity.setId(resultSet.getLong("id"));
        entity.setName(resultSet.getString("name"));
        entity.setEnabled(resultSet.getInt("enabled") == 1);
        entity.setListenPort(resultSet.getInt("listen_port"));
        entity.setForwardHost(resultSet.getString("forward_host"));
        entity.setForwardPort(resultSet.getInt("forward_port"));
        entity.setConfigJson(resultSet.getString("config_json"));
        entity.setCreatedAt(resultSet.getString("created_at"));
        entity.setUpdatedAt(resultSet.getString("updated_at"));
        return entity;
    }
}
