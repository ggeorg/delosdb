package io.github.ggeorg.delosdb.engine.extension.type;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.type.TypeCapabilities;
import io.github.ggeorg.delosdb.spi.type.TypeDescriptor;
import io.github.ggeorg.delosdb.spi.type.TypeProvider;

import java.util.List;

/**
 * Metadata-only TypeProvider for Derby's built-in SQL type catalog.
 */
@InternalApi
public final class BuiltInDerbyTypeProvider implements TypeProvider {
    private static final List<TypeDescriptor> TYPES = List.of(
            scalar("BOOLEAN", "BOOLEAN", "java.lang.Boolean", true, true),
            scalar("SMALLINT", "SMALLINT", "java.lang.Short", true, true),
            scalar("INTEGER", "INTEGER", "java.lang.Integer", true, true),
            scalar("BIGINT", "BIGINT", "java.lang.Long", true, true),
            scalar("REAL", "REAL", "java.lang.Float", true, true),
            scalar("DOUBLE", "DOUBLE", "java.lang.Double", true, true),
            scalar("DECIMAL", "DECIMAL", "java.math.BigDecimal", true, true),
            scalar("CHAR", "CHAR", "java.lang.String", true, true),
            scalar("VARCHAR", "VARCHAR", "java.lang.String", true, true),
            scalar("LONG VARCHAR", "LONGVARCHAR", "java.lang.String", true, false),
            scalar("DATE", "DATE", "java.sql.Date", true, true),
            scalar("TIME", "TIME", "java.sql.Time", true, true),
            scalar("TIMESTAMP", "TIMESTAMP", "java.sql.Timestamp", true, true),
            scalar("BLOB", "BLOB", "java.sql.Blob", true, false),
            scalar("CLOB", "CLOB", "java.sql.Clob", true, false)
    );

    @Override
    public String name() {
        return BuiltInExtensions.BUILTIN_TYPE_PROVIDER;
    }

    @Override
    public List<TypeDescriptor> types() {
        return TYPES;
    }

    @Override
    public TypeCapabilities capabilities() {
        return TypeCapabilities.derbyBuiltIns();
    }

    private static TypeDescriptor scalar(
            String typeName,
            String jdbcTypeName,
            String javaTypeName,
            boolean nullable,
            boolean comparable) {
        return TypeDescriptor.scalar(
                BuiltInExtensions.BUILTIN_TYPE_PROVIDER,
                typeName,
                jdbcTypeName,
                javaTypeName,
                nullable,
                comparable);
    }
}
