
import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Scanner;

import okhttp3.*;

public class main {

    public static void main(String[] args) throws IOException, SQLException {

        final String API_URL = "https://paper-api.alpaca.markets";
        final String API_KEY_ID = "PKVZVHGYI6YIVM1BYNNC";
        final String API_SECRET_KEY = "9H0wkaHycnQpp9y0lNY2CRSfwg9IHyiFisig9ShC"; // Hide this
        final String jdbcUrl = System.getenv("DB_URL");
        final String username = System.getenv("DB_USERNAME");
        final String password = System.getenv("DB_PASSWORD");
        // System.out.println(jdbcUrl+ username+ password);
        Request request = new Request.Builder()
                .url(API_URL + "/v2/assets")
                .addHeader("APCA-API-KEY-ID", API_KEY_ID)
                .addHeader("APCA-API-SECRET-KEY", API_SECRET_KEY)
                .build();
        OkHttpClient client = new OkHttpClient();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                java.sql.Connection connection = DriverManager.getConnection(jdbcUrl, username,
                        password);
                Scheduler taskScheduler = new Scheduler(connection, responseBody);
                taskScheduler.scheduleDailyTask();
                // taskScheduler.schedulePriceUpdate();
                System.out.println("--[ Trading APP ]--");

            }
        }
    }

    private static String getStrFromUser(String message) {
        Scanner s = new Scanner(System.in);
        System.out.print(message);
        return s.nextLine();
    }

}
