import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class UpdateDB {
    Connection connection;
    String table;

    public UpdateDB(Connection connection, String table) {
        this.connection = connection;
        this.table = table;
        List<String> resultList = fetchData();
    }

    public List<String> fetchData() {
        List<String> dataList = new ArrayList<>();
        String sql = "SELECT your_column FROM ?"; 
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String value = resultSet.getString(table);
                    dataList.add(value);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return dataList;
    }

    public void UpdateAll() {

    }

}
