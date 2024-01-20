
import java.io.IOException;
import java.sql.Connection;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalTime;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.Response;

// ---- Much easier to just do it with cron jobs ----
public class Scheduler {
    static Connection con;
    static String res;
    private ScheduledExecutorService scheduler;

    public Scheduler(Connection connection, String response) {
        con = connection;
        res = response;
        // ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    }

    public void schedulePriceUpdate() { // 0 5 * * * every 5
        scheduler.scheduleAtFixedRate(() -> {
            System.out.println("Task executed at: " + System.currentTimeMillis());
        }, 0, 5, TimeUnit.MINUTES);
    }

    public void scheduleDailyTask() throws IllegalStateException {// 25 15 * * *
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        // Calculate time between now and target time
        LocalTime now = LocalTime.now();
        LocalTime targetTime = LocalTime.of(18, 21); // 5 min before market opens in the US
        long initialDelayMinutes = calculateInitialDelay(now, targetTime); // Finds out when its supposed to run

        // GetApi.insertInto(con, res); // Do it immediately when starting ???
        // Here the schedule counts down and when it hits it Updates all newly added
        // NASDAQ stocks
        try {
            scheduler.schedule(() -> {
                GetApi.insertInto(con, res);
                System.out.println("Task executed at: " + LocalTime.now());
            }, initialDelayMinutes, TimeUnit.MINUTES);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // WORKS BUT gives an error WHEN FINISHED WITH TASK
    }

    private long calculateInitialDelay(LocalTime now, LocalTime targetTime) {
        Duration nextExce = Duration.between(now, targetTime);
        if (nextExce.isNegative()) { // If the target time has passed it will find tomorrows time until excecution
            nextExce = nextExce.plusDays(1);
        }
        return nextExce.toMinutes();
    }

    static class priceUpdate {
        private ScheduledExecutorService scheduler;

        public priceUpdate() {
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

            // Schedule a task to run every 5 minutes
            scheduler.scheduleAtFixedRate(() -> {
                System.out.println("Task executed at: " + System.currentTimeMillis());
            }, 0, 5, TimeUnit.MINUTES);
        }
    }
}
