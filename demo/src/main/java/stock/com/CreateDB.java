package stock.com;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class CreateDB {
    public static void main(String[] args) {
        String jdbcUrl = "jdbc:postgresql://localhost:5432/postgres";
        String username = "postgres";
        String password = args[0];

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {

            String sql = "SELECT * from stock";

            // Start the transaction
            connection.setAutoCommit(false);

            try (PreparedStatement statement = connection.prepareStatement(sql)) {

                // If using PreparedStatement with parameters, you can set them here.
                // For example, if there's a ? in the SQL:
                // statement.setString(1, "value_for_first_question_mark");

                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String data = resultSet.getString("company");
                        System.out.println(data);
                    }
                }

                // Commit the transaction
                connection.commit();

            } catch (SQLException e) {
                connection.rollback(); // Roll back the transaction if there's an error
                e.printStackTrace();
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
