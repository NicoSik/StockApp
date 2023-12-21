
import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.*;

public class main {

    public static void main(String[] args) throws IOException, SQLException {

        final String API_URL = "https://paper-api.alpaca.markets";
        final String API_KEY_ID = "PKVZVHGYI6YIVM1BYNNC";
        final String API_SECRET_KEY = "9H0wkaHycnQpp9y0lNY2CRSfwg9IHyiFisig9ShC";
        final String jdbcUrl = "jdbc:postgresql://localhost:5433/postgres?charSet=UTF-8";
        final String username = "postgres";
        final String password = "Cypisek00";

        Request request = new Request.Builder()
                .url(API_URL + "/v2/assets")
                .addHeader("APCA-API-KEY-ID", API_KEY_ID)
                .addHeader("APCA-API-SECRET-KEY", API_SECRET_KEY)
                .build();
        OkHttpClient client = new OkHttpClient();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {

                java.sql.Connection connection = DriverManager.getConnection(jdbcUrl, username,
                        password);
                Scheduler taskScheduler = new Scheduler(connection, response); 
                taskScheduler.scheduleDailyTask();
                // taskScheduler.s
                System.out.println("--[ Trading APP ]--");

            }
        }
    }

    private static String getStrFromUser(String message) {
        Scanner s = new Scanner(System.in);
        System.out.print(message);
        return s.nextLine();
    }

    private static Integer getIntFromUser(String message, boolean canBeBlank) {
        while (true) {
            String str = getStrFromUser(message);
            if (str.equals("") && canBeBlank) {
                return null;
            }
            try {
                return Integer.valueOf(str);
            } catch (NumberFormatException ex) {
                System.out.println("Please provide an integer or leave blank.");
            }
        }
    }
}
