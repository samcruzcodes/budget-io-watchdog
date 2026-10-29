package io.budget.watchdog.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/stream}: live events as Server-Sent Events, {@code transaction} for each new transaction and
 * {@code alert} for each new alert. In a browser: {@code new EventSource("/api/stream")}.
 */
@RestController
class StreamController {

    private final LiveFeed liveFeed;

    StreamController(LiveFeed liveFeed) {
        this.liveFeed = liveFeed;
    }

    @GetMapping(path = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        return liveFeed.connect();
    }
}
