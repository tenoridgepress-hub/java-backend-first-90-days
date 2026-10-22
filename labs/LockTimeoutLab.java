import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class LockTimeoutLab {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) throw new IllegalArgumentException("JDBC URL, username and password required");
        try (var setup = DriverManager.getConnection(arguments[0], arguments[1], arguments[2]);
             var first = DriverManager.getConnection(arguments[0], arguments[1], arguments[2]);
             var second = DriverManager.getConnection(arguments[0], arguments[1], arguments[2])) {
            try (var statement = setup.createStatement()) {
                statement.execute("CREATE TABLE lock_lab_order (id BIGINT PRIMARY KEY, quantity INTEGER NOT NULL)");
                statement.execute("INSERT INTO lock_lab_order VALUES (1, 2)");
            }
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            try {
                try (var statement = first.createStatement();
                     var rows = statement.executeQuery("SELECT quantity FROM lock_lab_order WHERE id = 1 FOR UPDATE")) {
                    if (!rows.next() || rows.getInt(1) != 2) throw new AssertionError("Missing fixture");
                }
                try (var statement = second.createStatement()) {
                    statement.execute("SET LOCAL lock_timeout = '150ms'");
                    long started = System.nanoTime();
                    try {
                        statement.executeQuery("SELECT quantity FROM lock_lab_order WHERE id = 1 FOR UPDATE");
                        throw new AssertionError("Expected lock timeout");
                    } catch (SQLException expected) {
                        requireState("55P03", expected);
                        System.out.println("LOCK_TIMEOUT sqlState=" + expected.getSQLState()
                            + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000);
                    }
                    try {
                        statement.executeQuery("SELECT 1");
                        throw new AssertionError("Expected aborted transaction");
                    } catch (SQLException expected) {
                        requireState("25P02", expected);
                        System.out.println("ABORTED_TRANSACTION sqlState=" + expected.getSQLState());
                    }
                }
                second.rollback();
                first.commit();
                try (var statement = second.createStatement();
                     var rows = statement.executeQuery("SELECT quantity FROM lock_lab_order WHERE id = 1 FOR UPDATE")) {
                    if (!rows.next() || rows.getInt(1) != 2) throw new AssertionError("Recovery failed");
                }
                second.commit();
                System.out.println("RECOVERED after rollback and holder commit");
            } finally {
                first.rollback();
                second.rollback();
                try (var statement = setup.createStatement()) {
                    statement.execute("DROP TABLE lock_lab_order");
                }
            }
        }
    }

    private static void requireState(String required, SQLException failure) throws SQLException {
        if (!required.equals(failure.getSQLState())) throw failure;
    }
}
