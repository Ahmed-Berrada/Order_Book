package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.journal.SealKeys;
import io.github.ahmedberrada.lob.service.MatchingService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/** The venue seals its journals with the keys it is configured with (RS-013). */
@Rulebook("RS-013")
class JournalSealConfigurationTest {

    @TempDir
    Path directory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(VenueConfiguration.class);

    @Test
    void configuredKeysSealTheJournalsAndAPrivateKeyAloneStopsTheVenue() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path privateKey = Files.writeString(directory.resolve("seal.pem"), SealKeys.pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
        Path publicKey = Files.writeString(directory.resolve("seal.pub"), SealKeys.pem("PUBLIC KEY", keys.getPublic().getEncoded()));
        String[] venue = {"lob.data-directory=" + directory.resolve("data"), "lob.fsync=OS",
            "lob.instruments[0].symbol=AAPL", "lob.instruments[0].tick-size=0.01",
            "lob.instruments[0].max-order-quantity=100", "lob.instruments[0].max-price=1000.00"};

        runner.withPropertyValues(venue).withPropertyValues("lob.journal-seal.private-key=" + privateKey,
                "lob.journal-seal.public-key=" + publicKey).run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(MatchingService.class).submit("AAPL", new LimitOrder(Side.BUY, 100, 1)).join();
                });
        assertThat(directory.resolve("data/AAPL/commands.seal")).exists().isNotEmptyFile();

        runner.withPropertyValues(venue).withPropertyValues("lob.journal-seal.private-key=" + privateKey)
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(venue).run(context -> assertThat(context).hasNotFailed());     // no keys: no seals
    }
}
