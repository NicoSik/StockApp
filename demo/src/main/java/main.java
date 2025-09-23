import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Scanner;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

import okhttp3.*;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

public class main {

    private static Properties loadEnvFile() {
        Properties props = new Properties();
        try {
            String content = new String(Files.readAllBytes(Paths.get(".env")));
            String[] lines = content.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    props.setProperty(parts[0].trim(), parts[1].trim());
                }
            }
        } catch (IOException e) {
            System.err.println("Error loading .env file: " + e.getMessage());
            System.err.println("Please ensure .env file exists in the project root directory");
        }
        return props;
    }

    public static void main(String[] args) throws IOException, SQLException {
        // Load environment variables from .env file
        Properties env = loadEnvFile();
        
        final String API_URL = "https://paper-api.alpaca.markets";
        final String API_KEY_ID = env.getProperty("API_KEY_ID");
        final String API_SECRET_KEY = env.getProperty("API_SECRET_KEY");
        final String jdbcUrl = env.getProperty("DB_URL");

        final String username = env.getProperty("DB_USERNAME");
        final String password = env.getProperty("DB_PASSWORD");

        // Validate that all required environment variables are set
        if (API_KEY_ID == null || API_SECRET_KEY == null || jdbcUrl == null || username == null || password == null) {
            System.err.println("Error: Required configuration values are not set in .env file.");
            System.err.println("Please ensure .env file contains: API_KEY_ID, API_SECRET_KEY, DB_URL, DB_USERNAME, DB_PASSWORD");
            System.exit(1);
        }

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
