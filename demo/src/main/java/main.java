import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Scanner;

import okhttp3.*;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

public class main {

    public static void main(String[] args) throws IOException, SQLException {
        final String API_URL = "https://paper-api.alpaca.markets";
        final String API_KEY_ID = "PKVZVHGYI6YIVM1BYNNC";
        final String API_SECRET_KEY = "9H0wkaHycnQpp9y0lNY2CRSfwg9IHyiFisig9ShC"; // Hide this
        final String jdbcUrl = "jdbc:postgresql://localhost:5433/postgres"; // System.getenv("DB_URL");

        final String username = "postgres"; // System.getenv("DB_USERNAME");
        final String password = "Cypisek00"; // System.getenv("DB_PASSWORD");

        Request request = new Request.Builder()
                .url(API_URL + "/v2/assets")
                .addHeader("APCA-API-KEY-ID", API_KEY_ID)
                .addHeader("APCA-API-SECRET-KEY", API_SECRET_KEY)
                .build();
        OkHttpClient client = new OkHttpClient();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                java.sql.Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                Scheduler taskScheduler = new Scheduler(connection, responseBody);
                taskScheduler.scheduleDailyTask();
                System.out.println("--[ Trading APP ]--");
            }
            System.out.println("Response: " + response);
        }

        // Configure Javalin to serve static files from the 'public' directory
        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH); // Ensure files are in /src/main/resources/public
        }).start(4567);

        // Redirect root URL to serve static HTML (test.html) file from 'public'
        app.get("/", ctx -> {
            ctx.redirect("/index.html"); // Serve test.html as a static file
        });

    }

    // Utility function to get input from the user
    private static String getStrFromUser(String message) {
        Scanner s = new Scanner(System.in);
        System.out.print(message);
        return s.nextLine();
    }
}
