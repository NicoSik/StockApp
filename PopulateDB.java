
// import java.sql.Connection;
// import java.sql.DriverManager;
// import java.sql.PreparedStatement;
// import java.sql.SQLException;

// public class PopulateDB {
// String jdbcUrl = "jdbc:postgresql://localhost:5432/postgres";
// String username = "postgres";
// String password = "Cypisek00";
// private static final String API_KEY = "YOUR_API_KEY_HERE";

// public PopulateDB() throws SQLException {
// try (Connection connection = DriverManager.getConnection(jdbcUrl, username,
// password)) {

// String sql = "INSERT into stock (symbol, company) values ('ADBE','Adobe
// Inc.')";
// try (PreparedStatement statement = connection.prepareStatement(sql)) {

// int affectedRows = statement.executeUpdate();
// if (affectedRows > 0) {
// System.out.println("Insert successful!");
// }
// }
// }
// }
// }
// s9dnLpsfqytqixdVHDv_