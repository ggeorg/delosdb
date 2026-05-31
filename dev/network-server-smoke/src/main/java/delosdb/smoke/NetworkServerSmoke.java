package delosdb.smoke;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.apache.derby.drda.NetworkServerControl;

public final class NetworkServerSmoke {
    private NetworkServerSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: NetworkServerSmoke <derby-system-home>");
        }

        Path derbyHome = Path.of(args[0]).toAbsolutePath();
        deleteRecursively(derbyHome);
        Files.createDirectories(derbyHome);
        System.setProperty("derby.system.home", derbyHome.toString());

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        int port = freeLoopbackPort(loopback);
        NetworkServerControl server = new NetworkServerControl(loopback, port);

        boolean started = false;
        try {
            server.start(new PrintWriter(System.out, true));
            started = true;
            waitForServer(server);
            runJdbcRoundTrip(port);
            System.out.println("DelosDB Network Server smoke test passed on port " + port + ".");
        } finally {
            if (started) {
                try {
                    server.shutdown();
                } catch (Exception shutdownFailure) {
                    System.err.println("Network Server shutdown reported: " + shutdownFailure.getMessage());
                }
            }
        }
    }

    private static void runJdbcRoundTrip(int port) throws Exception {
        Class.forName("org.apache.derby.jdbc.ClientDriver");
        String databaseUrl = "jdbc:derby://127.0.0.1:" + port + "/networkSmokeDb;create=true";
        try (Connection connection = DriverManager.getConnection(databaseUrl);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table NETWORK_SMOKE(ID int primary key, VALUE varchar(32))");
            statement.executeUpdate("insert into NETWORK_SMOKE values (1, 'network-ok')");
            try (ResultSet resultSet = statement.executeQuery("select VALUE from NETWORK_SMOKE where ID = 1")) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Network smoke row was not returned");
                }
                String value = resultSet.getString(1);
                if (!"network-ok".equals(value)) {
                    throw new IllegalStateException("Unexpected network smoke value: " + value);
                }
            }
        }

        try {
            DriverManager.getConnection("jdbc:derby://127.0.0.1:" + port + "/networkSmokeDb;shutdown=true");
        } catch (SQLException expected) {
            // Derby reports a successful database shutdown with an SQLException.
        }
    }

    private static void waitForServer(NetworkServerControl server) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                server.ping();
                return;
            } catch (Exception notReadyYet) {
                lastFailure = notReadyYet;
                Thread.sleep(250L);
            }
        }
        throw new IllegalStateException("Network Server did not become ready", lastFailure);
    }

    private static int freeLoopbackPort(InetAddress loopback) throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 50, loopback)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path item : stream.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
