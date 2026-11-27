package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.SelfTradeMode;
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
import java.time.Duration;
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
 * @param marketData       the market data feed (ADR-0007)
 * @param binaryFeed       the binary market data feed (ADR-0008), off unless enabled
 */
@Validated
@ConfigurationProperties("lob")
public record LobProperties(
        @NotNull Path dataDirectory,
        @DefaultValue("10000") @Positive int queueCapacity,
        @DefaultValue("EVERY_COMMAND") @NotNull FsyncPolicy fsync,
        @DefaultValue("10000") @PositiveOrZero int snapshotInterval,
        @NotEmpty List<@Valid InstrumentProperties> instruments,
        @DefaultValue @Valid FixProperties fix,
        @DefaultValue @Valid MarketDataProperties marketData,
        @DefaultValue @Valid BinaryFeedProperties binaryFeed) {

    /**
     * The binary feed (docs/interfaces/market-data-binary.md).
     *
     * @param streamHost    where packets go: a multicast group or a unicast address
     * @param servicePort   TCP retransmission and snapshot service, on {@code serviceAddress}
     * @param retention     messages kept for retransmission
     */
    public record BinaryFeedProperties(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("127.0.0.1") @NotBlank String streamHost,
            @DefaultValue("9900") @Positive int streamPort,
            @DefaultValue("127.0.0.1") @NotBlank String serviceAddress,
            @DefaultValue("9901") @PositiveOrZero int servicePort,
            @DefaultValue("1000000") @Positive int retention,
            @DefaultValue("1s") Duration heartbeat,
            @DefaultValue("100000") @Positive int queueCapacity) {
    }

    /**
     * The WebSocket market data feed.
     *
     * @param queueCapacity messages buffered per connection before it is closed as a slow consumer (MD-008)
     */
    public record MarketDataProperties(@DefaultValue("1000") @Positive int queueCapacity) {
    }

    /**
     * The FIX acceptor. It listens on the loopback interface unless told otherwise: sessions are not
     * authenticated until phase 8.
     *
     * @param dropCopySessions read-only sessions receiving a copy of every execution report (DC-001)
     * @param storeDirectory sequence numbers and sent messages; defaults to {@code <data-directory>/fix}
     * @param selfTradeMode  what happens when a member's order meets its own resting order; every member
     *                       session is its own self-trade prevention group (SP-001)
     */
    public record FixProperties(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("127.0.0.1") @NotBlank String bindAddress,
            @DefaultValue("9878") int port,
            @DefaultValue("LOB") @NotBlank String senderCompId,
            @DefaultValue List<String> members,
            @DefaultValue List<String> dropCopySessions,
            Path storeDirectory,
            @DefaultValue("CANCEL_NEWEST") SelfTradeMode selfTradeMode) {
    }

    /** One listed instrument. */
    public record InstrumentProperties(
            @NotBlank String symbol,
            @NotNull @Positive BigDecimal tickSize,
            @Positive long maxOrderQuantity,
            @NotNull @Positive BigDecimal maxPrice,
            @DefaultValue List<@Valid TickBandProperties> tickRegime,
            @PositiveOrZero long volatilityBandBps) {

        InstrumentConfig toConfig() {
            return new InstrumentConfig(symbol, tickSize, maxOrderQuantity, maxPrice,
                    tickRegime.stream().map(b -> new InstrumentConfig.PriceBand(b.fromPrice(), b.step())).toList(),
                    volatilityBandBps);
        }
    }

    /** One band of an instrument's tick-size regime. */
    public record TickBandProperties(@NotNull @Positive BigDecimal fromPrice, @NotNull @Positive BigDecimal step) {
    }

    List<InstrumentConfig> instrumentConfigs() {
        return instruments.stream().map(InstrumentProperties::toConfig).toList();
    }
}
