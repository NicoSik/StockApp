
import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import okhttp3.*;
import spark.ModelAndView;
import org.eclipse.jetty.server.Server;
import spark.ModelAndView;
import spark.template.velocity.VelocityTemplateEngine;
import static spark.Spark.*;

public class main {

    public static void main(String[] args) throws IOException, SQLException {
        port(4567);
        final String API_URL = "https://paper-api.alpaca.markets";
        final String API_KEY_ID = "PKVZVHGYI6YIVM1BYNNC";
        final String API_SECRET_KEY = "9H0wkaHycnQpp9y0lNY2CRSfwg9IHyiFisig9ShC"; // Hide this
        final String jdbcUrl = "jdbc:postgresql://localhost:5433/postgres"; // System.getenv("DB_URL");

        final String username = "postgres";// System.getenv("DB_USERNAME"); // postgres
        final String password = "Cypisek00";// System.getenv("DB_PASSWORD");// Cypisekk00
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
                List<String> stockList = List.of("AAPL", "GOOGL", "MSFT");
                get("/test", (req, res) -> {
                    System.out.println("Test route hit!");
                    return "Test route works!";
                });
                awaitInitialization();

                // get("/stocks", (req, res) -> {
                // try {
                // Map<String, Object> model = new HashMap<>();
                // model.put("stocks", stockList); // Pass stock list to HTML
                // System.out.println("Stocks: " + stockList);
                // return new ModelAndView(model, "webapp/index.vtl");

                // } catch (Exception e) {
                // System.out.println("Error: " + e.getMessage());
                // e.printStackTrace(); // Log the exception to the console
                // res.status(500); // Set HTTP status to 500
                // Map<String, Object> errorModel = new HashMap<>();
                // errorModel.put("error", "Internal Server Error"); // Optionally pass an error
                // message
                // return new ModelAndView(errorModel, "webapp/error.vtl"); // Return an error
                // template
                // }
                // }, new VelocityTemplateEngine());

                // get("/stocks/:symbol", (req, res) -> {
                // String symbol = req.params(":symbol");
                // Map<String, Object> model = new HashMap<>();
                // model.put("symbol", symbol);
                // return new ModelAndView(model, "templates/stock.vtl");
                // }, new VelocityTemplateEngine());
                // taskScheduler.schedulePriceUpdate();
                System.out.println("--[ Trading APP ]--");

            }
            System.out.println("Response: " + response);
        }
    }

    private static String getStrFromUser(String message) {
        Scanner s = new Scanner(System.in);
        System.out.print(message);
        return s.nextLine();
    }

}
