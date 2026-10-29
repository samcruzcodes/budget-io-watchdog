package io.budget.watchdog.api;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes new transactions and alerts to every connected browser as Server-Sent Events. The Kafka listener hands each
 * event over and returns at once; one background thread does the sending, so a slow browser can't hold up Kafka. If
 * that thread falls 1,000 events behind, new events are dropped (and counted in the log) rather than piling up.
 *
 * <p>It only sees what this process handled: with several watchdog instances, each stream shows the cards on that
 * instance's partitions.
 */
@Component
public class LiveFeed implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LiveFeed.class);

    // A browser's EventSource reconnects by itself when this runs out.
    private static final Duration CONNECTION_TIMEOUT = Duration.ofMinutes(30);

    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();
    private final AtomicLong dropped = new AtomicLong();
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1_000), (event, executor) -> {
                long total = dropped.incrementAndGet();
                if (total % 1_000 == 1) {
                    log.warn("Live feed is behind: {} events dropped so far", total);
                }
            });
    private volatile boolean running;

    /** A new browser connection. */
    SseEmitter connect() {
        SseEmitter client = new SseEmitter(CONNECTION_TIMEOUT.toMillis());
        client.onCompletion(() -> clients.remove(client));
        client.onTimeout(() -> clients.remove(client));
        client.onError(error -> clients.remove(client));
        clients.add(client);
        try {
            // A comment line, which EventSource ignores. It sends the response headers right away.
            client.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            clients.remove(client);
        }
        return client;
    }

    /** Queues an event for every connected browser; returns at once. */
    public void publish(String eventName, Object data) {
        if (clients.isEmpty()) {
            return;
        }
        sender.execute(() -> {
            for (SseEmitter client : clients) {
                try {
                    client.send(SseEmitter.event().name(eventName).data(data));
                } catch (IOException | IllegalStateException e) {
                    clients.remove(client); // the browser went away
                }
            }
        });
    }

    @Override
    public void start() {
        running = true;
    }

    /**
     * Closes every stream at shutdown, before the web server's graceful shutdown starts: an open stream counts as a
     * request in progress, so otherwise shutdown would wait out its whole timeout.
     */
    @Override
    public void stop() {
        running = false;
        clients.forEach(SseEmitter::complete);
        clients.clear();
        sender.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** The highest phase stops first. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
