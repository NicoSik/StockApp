
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import com.google.gson.JsonObject;

public class PopulateDB {

    public PopulateDB() {

    }

    public void insertData(JsonObject jason, Connection connection) {
        // Get the data from the json object
        String symbol = jason.get("symbol").getAsString();
        String company = jason.get("name").getAsString();
        String market = jason.get("exchange").getAsString();

        String sql = "INSERT INTO stock (symbol, company, market) VALUES (?, ?, ?) ON CONFLICT DO NOTHING";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setString(2, company);
            statement.setString(3, market);
            int affectedRows = statement.executeUpdate();
            if (affectedRows > 0) {
                System.out.println("Insert successful!" + symbol);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

}
