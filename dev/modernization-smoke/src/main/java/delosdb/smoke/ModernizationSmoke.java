package delosdb.smoke;

import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

public final class ModernizationSmoke {
    private ModernizationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");

        try (Connection connection = DriverManager.getConnection("jdbc:derby:modernization-smoke-db;create=true")) {
            connection.setAutoCommit(false);

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("create table smoke_entity(id int primary key, name varchar(40), data blob(1K))");
                statement.addBatch("insert into smoke_entity(id, name) values (1, 'batch-one')");
                statement.addBatch("insert into smoke_entity(id, name) values (2, 'batch-two')");
                int[] counts = statement.executeBatch();
                if (counts.length != 2) {
                    throw new IllegalStateException("Expected two batch results but got " + counts.length);
                }
            }

            byte[] expectedBlob = new byte[] { 1, 2, 3, 4, 5 };
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into smoke_entity(id, name, data) values (?, ?, ?)")) {
                insert.setInt(1, 3);
                insert.setString(2, "prepared-blob");
                insert.setBytes(3, expectedBlob);
                if (insert.executeUpdate() != 1) {
                    throw new IllegalStateException("Prepared insert did not affect exactly one row");
                }
            }

            try (PreparedStatement select = connection.prepareStatement(
                    "select name, data from smoke_entity where id = ?")) {
                select.setInt(1, 3);
                try (ResultSet resultSet = select.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Prepared-select smoke row was not returned");
                    }
                    if (!"prepared-blob".equals(resultSet.getString(1))) {
                        throw new IllegalStateException("Unexpected smoke row name: " + resultSet.getString(1));
                    }
                    Blob blob = resultSet.getBlob(2);
                    try {
                        byte[] actualBlob = blob.getBytes(1, (int) blob.length());
                        if (!Arrays.equals(expectedBlob, actualBlob)) {
                            throw new IllegalStateException("Unexpected BLOB payload");
                        }
                    } finally {
                        blob.free();
                    }
                }
            }

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("select count(*) from smoke_entity")) {
                if (!resultSet.next() || resultSet.getInt(1) != 3) {
                    throw new IllegalStateException("Unexpected smoke row count");
                }
            }

            connection.commit();
        }

        shutdownDatabase();
        System.out.println("DelosDB Java 21 modernization smoke test passed.");
    }

    private static void shutdownDatabase() throws SQLException {
        try {
            DriverManager.getConnection("jdbc:derby:modernization-smoke-db;shutdown=true");
            throw new IllegalStateException("Derby shutdown should report SQLState 08006");
        } catch (SQLException shutdown) {
            if (!"08006".equals(shutdown.getSQLState())) {
                throw shutdown;
            }
        }
    }
}
