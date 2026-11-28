package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.PhaseChanged;
import io.github.ahmedberrada.lob.core.SetPhase;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Listed instruments and their books (docs/interfaces/rest-api.md). */
@RestController
@RequestMapping("/api/v1/instruments")
class InstrumentController {

    private final MatchingService service;
    private final TracedCommands commands;

    InstrumentController(MatchingService service, TracedCommands commands) {
        this.commands = commands;
        this.service = service;
    }

    @GetMapping
    CompletableFuture<List<InstrumentResponse>> instruments() {
        List<CompletableFuture<InstrumentResponse>> responses = service.instruments().stream()
                .map(i -> service.phase(i.symbol())
                        .thenApply(phase -> InstrumentResponse.of(i, service.isHalted(i.symbol()), phase)))
                .toList();
        return CompletableFuture.allOf(responses.toArray(CompletableFuture[]::new))
                .thenApply(done -> responses.stream().map(CompletableFuture::join).toList());
    }

    /**
     * Moves an instrument to another trading phase (TP-002). Until members and authentication exist
     * this needs the ADMIN role (AC-004).
     */
    @PutMapping("/{symbol}/phase")
    CompletableFuture<PhaseResponse> setPhase(@PathVariable String symbol, @Valid @RequestBody PhaseRequest request) {
        InstrumentConfig instrument = service.instrument(symbol).orElseThrow(() ->
                new RequestRefusedException(RequestRefusedException.Reason.UNKNOWN_INSTRUMENT, symbol));
        return commands.submit(symbol, new SetPhase(request.phase())).thenApply(batch -> {
            PhaseChanged changed = batch.events().stream().filter(PhaseChanged.class::isInstance)
                    .map(PhaseChanged.class::cast).findFirst()       // after the uncross's events, if any (TP-003)
                    .orElseThrow(() -> new PhaseChangeRejectedException(symbol, request.phase().name()));
            return PhaseResponse.from(batch, instrument, changed);
        });
    }

    /** The book is copied on the instrument's thread, so it is consistent as of one command. */
    @GetMapping("/{symbol}/book")
    CompletableFuture<BookResponse> book(@PathVariable String symbol,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int depth) {
        InstrumentConfig instrument = service.instrument(symbol).orElseThrow(() ->
                new RequestRefusedException(RequestRefusedException.Reason.UNKNOWN_INSTRUMENT, symbol));
        return service.book(symbol, depth).thenApply(book -> BookResponse.of(instrument, book));
    }
}
