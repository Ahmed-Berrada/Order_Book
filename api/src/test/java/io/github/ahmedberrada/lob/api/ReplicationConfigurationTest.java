package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.journal.ReplicaServer;
import io.github.ahmedberrada.lob.journal.ReplicationException;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import io.github.ahmedberrada.lob.service.MatchingService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/** A venue configured with a standby replicates to it before acknowledging (RS-014). */
@Rulebook({"RS-014", "RS-015"})
class ReplicationConfigurationTest {

    @TempDir
    Path directory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(VenueConfiguration.class);

    @Test
    void aConfiguredStandbyHoldsTheJournalAndAStandbyWithoutASecretStopsTheVenue() throws Exception {
        byte[] secret = "a replication secret of 32 bytes or more".getBytes(StandardCharsets.UTF_8);
        Path secretFile = Files.write(directory.resolve("replication.secret"), secret);
        String[] venue = {"lob.data-directory=" + directory.resolve("primary"), "lob.fsync=GROUP",
            "lob.instruments[0].symbol=AAPL", "lob.instruments[0].tick-size=0.01",
            "lob.instruments[0].max-order-quantity=100", "lob.instruments[0].max-price=1000.00"};
        try (ReplicaServer standby = ReplicaServer.start(directory.resolve("standby"), "127.0.0.1", 0, secret)) {
            runner.withPropertyValues(venue).withPropertyValues("lob.replication.standby-host=127.0.0.1",
                    "lob.replication.standby-port=" + standby.port(), "lob.replication.secret-file=" + secretFile)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        context.getBean(MatchingService.class).submit("AAPL", new LimitOrder(Side.BUY, 100, 1)).join();
                        assertThat(Files.readAllBytes(directory.resolve("standby/AAPL/commands.journal")))
                                .isEqualTo(Files.readAllBytes(directory.resolve("primary/AAPL/commands.journal")));

                        standby.close();                            // RS-015: no standby, no acknowledgement
                        MatchingService service = context.getBean(MatchingService.class);
                        assertThatThrownBy(() -> service.submit("AAPL", new LimitOrder(Side.BUY, 100, 1)).join())
                                .hasCauseInstanceOf(ReplicationException.class);
                        assertThatThrownBy(() -> service.book("AAPL", 5).join())
                                .as("the book in memory is not the venue's: its last command's outcome is unknown")
                                .hasCauseInstanceOf(RequestRefusedException.class);
                    });
        }
        runner.withPropertyValues(venue).withPropertyValues("lob.replication.standby-host=127.0.0.1")
                .run(context -> assertThat(context).hasFailed());
    }
}
