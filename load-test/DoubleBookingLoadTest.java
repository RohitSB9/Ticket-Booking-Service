import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 6: fires many truly-concurrent reservation requests at every seat of
 * a freshly created event and proves each seat has exactly one winner — not
 * zero (a lost update), not two-or-more (a double booking).
 *
 * Runs against the live, already-running Event Service (8081) and
 * Reservation Service (8082) — this is not a unit test with mocks, it's
 * hitting the real Redis lock over real HTTP. Run with:
 *
 *   java DoubleBookingLoadTest.java
 *
 * (JDK 21+ single-file source execution — no compile step, no dependencies
 * beyond the JDK standard library.)
 */
public class DoubleBookingLoadTest {

    private static final String EVENT_SERVICE = "http://localhost:8081";
    private static final String RESERVATION_SERVICE = "http://localhost:8082";
    private static final int SEATS = 20;
    private static final int CONCURRENT_REQUESTS_PER_SEAT = 10;

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        checkServiceUp(client, EVENT_SERVICE + "/api/events", "Event Service (8081)");
        checkServiceUp(client, RESERVATION_SERVICE + "/api/reservations", "Reservation Service (8082)");

        System.out.println("Creating event with " + SEATS + " seats...");
        String eventJson = post(client, EVENT_SERVICE + "/api/events", """
                {"name":"Load Test %s","venue":"Load Test Arena","startsAt":"2026-09-01T20:00:00Z","rows":1,"seatsPerRow":%d}"""
                .formatted(Instant.now(), SEATS));
        String eventId = extractFirst(eventJson, "\"id\":\"([0-9a-f-]{36})\"");
        List<String> seatIds = extractAll(eventJson, "\"id\":\"([0-9a-f-]{36})\",\"seatLabel\"");

        if (eventId == null || seatIds.size() != SEATS) {
            System.err.println("Failed to create event/seats as expected. Response was:\n" + eventJson);
            System.exit(2);
        }
        System.out.println("Event " + eventId + " created with " + seatIds.size() + " seats.");

        int totalRequests = seatIds.size() * CONCURRENT_REQUESTS_PER_SEAT;
        System.out.println("Firing " + totalRequests + " concurrent reservation requests ("
                + CONCURRENT_REQUESTS_PER_SEAT + " racing for each of the " + seatIds.size() + " seats)...");

        Map<String, AtomicInteger> winsPerSeat = new ConcurrentHashMap<>();
        for (String seatId : seatIds) {
            winsPerSeat.put(seatId, new AtomicInteger(0));
        }
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        CountDownLatch ready = new CountDownLatch(totalRequests);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String seatId : seatIds) {
                for (int i = 0; i < CONCURRENT_REQUESTS_PER_SEAT; i++) {
                    String userId = "load-user-" + UUID.randomUUID();
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        String body = """
                                {"eventId":"%s","seatId":"%s","userId":"%s"}"""
                                .formatted(eventId, seatId, userId);
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(RESERVATION_SERVICE + "/api/reservations"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();
                        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        switch (resp.statusCode()) {
                            case 201 -> {
                                created.incrementAndGet();
                                winsPerSeat.get(seatId).incrementAndGet();
                            }
                            case 409 -> conflict.incrementAndGet();
                            default -> {
                                other.incrementAndGet();
                                System.err.println("Unexpected status " + resp.statusCode() + " for seat " + seatId
                                        + ": " + resp.body());
                            }
                        }
                        return null;
                    }));
                }
            }

            // Wait for every virtual thread to be parked at the gate before
            // releasing it, so the burst is a genuine simultaneous spike,
            // not just a tight loop.
            if (!ready.await(30, TimeUnit.SECONDS)) {
                System.err.println("Timed out waiting for worker threads to reach the start gate.");
                System.exit(2);
            }
            Instant start = Instant.now();
            go.countDown();

            for (Future<Void> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            Duration elapsed = Duration.between(start, Instant.now());

            System.out.println();
            System.out.println("=== Request results ===");
            System.out.println("Total requests: " + totalRequests);
            System.out.println("201 Created:    " + created.get());
            System.out.println("409 Conflict:   " + conflict.get());
            System.out.println("Other/errors:   " + other.get());
            System.out.printf("Elapsed:        %dms (%.0f req/s)%n",
                    elapsed.toMillis(), totalRequests / Math.max(0.001, elapsed.toMillis() / 1000.0));

            List<String> zeroWinners = new ArrayList<>();
            List<String> doubleBooked = new ArrayList<>();
            for (Map.Entry<String, AtomicInteger> entry : winsPerSeat.entrySet()) {
                int wins = entry.getValue().get();
                if (wins == 0) {
                    zeroWinners.add(entry.getKey());
                } else if (wins > 1) {
                    doubleBooked.add(entry.getKey() + " (" + wins + " winners)");
                }
            }

            System.out.println();
            System.out.println("=== Per-seat outcome (source of truth: reservation-service response bodies) ===");
            System.out.println("Seats with exactly 1 winner: " + (seatIds.size() - zeroWinners.size() - doubleBooked.size())
                    + " / " + seatIds.size());
            if (!zeroWinners.isEmpty()) {
                System.out.println("Seats with 0 winners (LOST UPDATE): " + zeroWinners);
            }
            if (!doubleBooked.isEmpty()) {
                System.out.println("Seats with 2+ winners (DOUBLE BOOKING): " + doubleBooked);
            }

            System.out.println();
            System.out.println("=== Cross-check against Event Service seat state ===");
            String seatsJson = get(client, EVENT_SERVICE + "/api/events/" + eventId + "/seats");
            long reservedCount = countMatches(seatsJson, "\"status\":\"RESERVED\"");
            long soldCount = countMatches(seatsJson, "\"status\":\"SOLD\"");
            long availableCount = countMatches(seatsJson, "\"status\":\"AVAILABLE\"");
            // Payment Service is running live and processes seat-reserved
            // asynchronously (~300ms), so some seats may have already
            // advanced RESERVED -> SOLD by the time this GET runs — that's
            // expected, not a bug. The invariant that actually matters here
            // is that no seat that had 10 requests racing for it is still
            // sitting AVAILABLE.
            System.out.println("Seats RESERVED:  " + reservedCount + " / " + seatIds.size());
            System.out.println("Seats SOLD:      " + soldCount + " / " + seatIds.size()
                    + " (Payment Service already confirmed these — expected, not a bug)");
            System.out.println("Seats AVAILABLE: " + availableCount + " (should be 0)");

            boolean pass = zeroWinners.isEmpty()
                    && doubleBooked.isEmpty()
                    && other.get() == 0
                    && (reservedCount + soldCount) == seatIds.size()
                    && availableCount == 0;

            System.out.println();
            if (pass) {
                System.out.println("PASS — every seat got exactly one winner under " + totalRequests
                        + " concurrent requests. No double-booking, no lost seats.");
            } else {
                System.out.println("FAIL — see counts above.");
                System.exit(1);
            }
        }
    }

    private static void checkServiceUp(HttpClient client, String url, String label) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(3)).build();
            client.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println(label + " doesn't seem to be up at " + url + " (" + e.getMessage() + ").");
            System.err.println("Start it first — see README.md \"Running locally\".");
            System.exit(2);
        }
    }

    private static String post(HttpClient client, String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String get(HttpClient client, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String extractFirst(String json, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static List<String> extractAll(String json, String pattern) {
        List<String> results = new ArrayList<>();
        Matcher m = Pattern.compile(pattern).matcher(json);
        while (m.find()) {
            results.add(m.group(1));
        }
        return results;
    }

    private static long countMatches(String haystack, String literal) {
        return Pattern.compile(Pattern.quote(literal)).matcher(haystack).results().count();
    }
}
