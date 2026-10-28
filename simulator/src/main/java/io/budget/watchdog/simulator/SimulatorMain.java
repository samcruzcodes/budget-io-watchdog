package io.budget.watchdog.simulator;

import java.time.InstantSource;
import java.time.ZoneOffset;
import org.apache.kafka.common.errors.InterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point: reads the configuration, builds the simulated world, and publishes it to Kafka. */
public final class SimulatorMain {

    private static final Logger log = LoggerFactory.getLogger(SimulatorMain.class);

    private SimulatorMain() {
    }

    public static void main(String[] args) {
        SimulatorConfig config = SimulatorConfig.fromEnvironment(System.getenv());
        log.info("Starting: {}", config);

        Simulation simulation = Simulation.create(config);
        SimClock clock = new SimClock(config.startDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
                config.daysPerMinute(), InstantSource.system(), Thread::sleep);

        // `docker stop` sends SIGTERM. The JVM then runs shutdown hooks; this one interrupts the run and waits for it
        // to finish closing the producer, so messages already sent aren't lost.
        Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            mainThread.interrupt();
            try {
                mainThread.join(15_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }));

        KafkaSink sink = new KafkaSink(config.kafkaBootstrapServers(), InstantSource.system());
        try {
            long published = new SimulatorRunner(simulation, clock, sink).run(config.startDate(), config.durationDays());
            log.info("Finished: {} transactions published", published);
        } catch (InterruptedException | InterruptException e) {
            log.info("Stopping");
        } finally {
            Thread.interrupted(); // clear the interrupt so close() can flush what was already sent
            sink.close();
        }
    }
}
