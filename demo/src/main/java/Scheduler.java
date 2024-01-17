
import java.sql.Connection;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalTime;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.Response;

//Gonna try using cron jobs aswell
public class Scheduler {
    static Connection con;
    static Response res;
    private ScheduledExecutorService scheduler;

    public Scheduler(Connection connection, Response response) {
        con = connection;
        res = response;
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    }

    public void schedulePriceUpdate() { // 0 5 * * * every 5 min
        scheduler.scheduleAtFixedRate(() -> {
            System.out.println("Task executed at: " + System.currentTimeMillis());
        }, 0, 5, TimeUnit.MINUTES);
    }

    public void scheduleDailyTask() {// 25 15 * * *
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        // Calculate time between now and target time
        LocalTime now = LocalTime.now();
        LocalTime targetTime = LocalTime.of(15, 25);
        Duration initialDelay = Duration.between(now, targetTime);

        // Schedule a task to run every day at the specified time
        scheduler.scheduleAtFixedRate(() -> {
            GetApi.insertInto(con, res);
            System.out.println("Task executed at: " + LocalTime.now());
        }, initialDelay.toMinutes(), 24 * 60, TimeUnit.MINUTES);
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
