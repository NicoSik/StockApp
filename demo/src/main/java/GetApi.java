import okhttp3.*;

import java.sql.DriverManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class GetApi {

    public static void insertInto(java.sql.Connection connection, Response response) {

        String sql = "SELECT symbol,company,market from stokck";
        PopulateDB db = new PopulateDB();
        try {
            String responseBody = response.body().string();
            JsonArray jsonArray = JsonParser.parseString(responseBody).getAsJsonArray();
            for (JsonElement obj : jsonArray) {
                if (((JsonObject) obj).get("status").getAsString().equals("active")) {
                    // There are some duplicates and undtradble stocks
                    JsonObject jason = obj.getAsJsonObject();
                    db.insertData(jason, connection);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
