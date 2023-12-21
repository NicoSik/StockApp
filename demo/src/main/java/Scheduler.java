
import java.sql.Connection;
import java.text.SimpleDateFormat;
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

    public void schedulePriceUpdate() {
        scheduler.scheduleAtFixedRate(() -> {
            System.out.println("Task executed at: " + System.currentTimeMillis());
        }, 0, 5, TimeUnit.MINUTES);
    }

    public void scheduleDailyTask() {
        scheduler.scheduleAtFixedRate(() -> {
            System.out.println("Task executed at: " + System.currentTimeMillis());
        }, 0, 5, TimeUnit.MINUTES);
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
// import java.util.concurrent.Executors;
// import java.util.concurrent.ScheduledExecutorService;
// import java.util.concurrent.TimeU

// public class DailyTaskScheduler {

// ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

// // Schedule a task to run every day at 2:30 PM
// r.scheduleAtFixedRate(() -> {
// our task logic
// S

// }

// ate static long calculateInitialDelay(int targetHour, int targetMin
// te) {
// java.time.LocalTime targetTi

// U

// }

// return durationUntilNextExecution.toMillis();
// }
// }
