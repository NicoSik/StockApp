
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import com.google.gson.JsonObject;

public class CreateDB {

    public CreateDB() {

    }

    public void insertData(JsonObject jason) {
        String jdbcUrl = "jdbc:postgresql://localhost:5433/postgres";
        String username = "postgres";
        String password = "Cypisek00";

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username,
                password)) {

            // Get the data from json object
            String symbol = jason.get("symbol").getAsString();
            String company = jason.get("name").getAsString();
            String market = jason.get("exchange").getAsString();

            String sql = "INSERT INTO stock (symbol, company, market) VALUES (?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, symbol);
                statement.setString(2, company);
                statement.setString(3, market);
                int affectedRows = statement.executeUpdate();
                if (affectedRows > 0) {
                    System.out.println("Insert successful!");
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}