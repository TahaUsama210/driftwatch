package io.github.tahausama210.driftwatch.common.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Conversions between {@code java.time}/{@code UUID} and the types the PostgreSQL driver binds
 * natively. pgjdbc maps {@code timestamptz} to {@link OffsetDateTime}, not {@link Instant}.
 */
public final class JdbcValues {

    private JdbcValues() {}

    public static OffsetDateTime timestamptz(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        Instant value = nullableInstant(rs, column);
        if (value == null) {
            throw new SQLException("Column " + column + " is unexpectedly null");
        }
        return value;
    }

    public static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        UUID value = nullableUuid(rs, column);
        if (value == null) {
            throw new SQLException("Column " + column + " is unexpectedly null");
        }
        return value;
    }

    public static @Nullable UUID nullableUuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }
}
