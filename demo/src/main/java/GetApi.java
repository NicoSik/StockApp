import okhttp3.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class GetApi {

    private static final String API_URL = "https://paper-api.alpaca.markets";
    private static final String API_KEY_ID = "PKVZVHGYI6YIVM1BYNNC";
    private static final String API_SECRET_KEY = "9H0wkaHycnQpp9y0lNY2CRSfwg9IHyiFisig9ShC";

    public static void main(String[] args) {
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(API_URL + "/v2/assets")
                .addHeader("APCA-API-KEY-ID", API_KEY_ID)
                .addHeader("APCA-API-SECRET-KEY", API_SECRET_KEY)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                CreateDB db = new CreateDB();
                String responseBody = response.body().string();
                JsonArray jsonArray = JsonParser.parseString(responseBody).getAsJsonArray();
                for (JsonElement obj : jsonArray) {
                    if (((JsonObject) obj).get("status").getAsString().equals("active")) {
                        // There are some duplicates and undtradble stocks
                        JsonObject jason = obj.getAsJsonObject();
                        db.insertData(jason);
                    }
                }

            } else {
                System.out.println("Error: " + response.code() + " " + response.message());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
