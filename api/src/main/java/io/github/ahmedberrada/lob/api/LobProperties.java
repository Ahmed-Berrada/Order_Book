package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The venue as declared in {@code application.yml} under {@code lob} (ADR-0005 §5). Validated at
 * startup: the application does not start on an incomplete configuration.
 *
 * @param dataDirectory    where each instrument's journals live, one directory per symbol
 * @param queueCapacity    commands an instrument can hold before refusing with {@code OVERLOADED}
 * @param fsync            when the command journal is flushed to the device
 * @param snapshotInterval commands between automatic snapshots; 0 disables them
 * @param instruments      the listed instruments, fixed for the life of the process
 * @param fix              the FIX gateway (ADR-0006), off unless enabled
 */
@Validated
@ConfigurationProperties("lob")
public record LobProperties(
        @NotNull Path dataDirectory,
        @DefaultValue("10000") @Positive int queueCapacity,
        @DefaultValue("EVERY_COMMAND") @NotNull FsyncPolicy fsync,
        @DefaultValue("10000") @PositiveOrZero int snapshotInterval,
        @NotEmpty List<@Valid InstrumentProperties> instruments,
        @DefaultValue @Valid FixProperties fix) {

    /**
     * The FIX acceptor.
     *
     * @param storeDirectory sequence numbers and sent messages; defaults to {@code <data-directory>/fix}
     */
    public record FixProperties(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("9878") int port,
            @DefaultValue("LOB") @NotBlank String senderCompId,
            @DefaultValue List<String> members,
            Path storeDirectory) {
    }

    /** One listed instrument. */
    public record InstrumentProperties(
            @NotBlank String symbol,
            @NotNull @Positive BigDecimal tickSize,
            @Positive long maxOrderQuantity,
            @NotNull @Positive BigDecimal maxPrice) {

        InstrumentConfig toConfig() {
            return new InstrumentConfig(symbol, tickSize, maxOrderQuantity, maxPrice);
        }
    }

    List<InstrumentConfig> instrumentConfigs() {
        return instruments.stream().map(InstrumentProperties::toConfig).toList();
    }
}
