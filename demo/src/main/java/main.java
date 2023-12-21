
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
                System.out.println("--[ Trading APP ]--");
                System.out.println(
                        "Vennligst velg et alternativ:\n 1. Søk etter symbol\n 2. Oppdater\n 3. Avslutt");
                Integer ch = getIntFromUser("Valg: ", true);

                if (ch == 1) {
                } else if (ch == 2) {
                    GetApi.insertInto(connection, response);
                }
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

    // public class FiveMinutesTaskScheduler {

    // public static void main(String[] args) {
    // ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    // // Schedule a task to run every 5 minutes
    // scheduler.scheduleAtFixedRate(() -> {
    // // Your task logic
    // System.out.println("Task executed at: " + System.currentTimeMillis());
    // }, 0, 5, TimeUnit.MINUTES);
    // }
    // }

public class DailyTaskScheduler {

    public static void main(String[] args) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        // Schedule a task to run every day at 2:30 PM
        scheduler.scheduleAtFixedRate(() -> {
            // Your task logic
            System.out.println("Task executed at: " + System.currentTimeMillis());
        }, calculateInitialDelay(14, 30), 24, TimeUnit.HOURS);
    }

    private static long calculateInitialDelay(int targetHour, int targetMinute) {
        java.time.LocalTime targetTime = java.time.LocalTime.of(targetHour, targetMinute);
        java.time.Duration durationUntilNextExecution = java.time.Duration.between(java.time.LocalTime.now(),
                targetTime);

        // If the target time has already passed today, calculate the duration until the
        // next occurrence
        if (durationUntilNextExecution.isNegative()) {
            durationUntilNextExecution = durationUntilNextExecution.plusDays(1);
        }

        return durationUntilNextExecution.toMillis();
    }
}

}