package io.budget.watchdog.simulator;

import java.io.IOException;
import java.time.Instant;
import java.time.InstantSource;
import java.util.UUID;
import org.apache.kafka.common.errors.InterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point: reads the configuration, builds the simulated world, and publishes it to Kafka. */
public final class SimulatorMain {

    private static final Logger log = LoggerFactory.getLogger(SimulatorMain.class);

    private SimulatorMain() {
    }

    public static void main(String[] args) throws IOException {
        SimulatorConfig config = SimulatorConfig.fromEnvironment(System.getenv());
        log.info("Starting: {}", config);

        // A restarted simulator continues after the newest transaction already on the topic. Without this it would
        // publish the same IDs again from the first day, and the watchdog would skip every one of them.
        Instant resumeFrom = config.resume() ? ResumePoint.find(config.kafkaBootstrapServers()).orElse(null) : null;
        log.info(resumeFrom == null ? "A new run from {}" : "Continuing an earlier run from {}",
                resumeFrom == null ? config.startDate() : resumeFrom);

        Simulation simulation = Simulation.create(config);
        SimClock clock = new SimClock(SimulatorRunner.firstMoment(config.startDate(), resumeFrom),
                config.daysPerMinute(), InstantSource.system(), config.startPaused());

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
        SimulatorRunner runner = new SimulatorRunner(simulation, clock, sink, resumeFrom, config.pauseAfterDays());
        DemoRunner demos = new DemoRunner(config.scenariosEnabled(), clock, sink, InstantSource.system(), UUID::randomUUID);
        ControlServer control = new ControlServer(config.controlPort(), clock, runner::progress, demos);
        control.start();
        try {
            long published = runner.run(config.startDate(), config.durationDays());
            log.info("Finished: {} transactions published", published);
        } catch (InterruptedException | InterruptException e) {
            log.info("Stopping");
        } finally {
            Thread.interrupted(); // clear the interrupt so close() can flush what was already sent
            control.stop();
            sink.close();
        }
    }
}
