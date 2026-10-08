package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Listed instruments and their books (docs/interfaces/rest-api.md). */
@RestController
@RequestMapping("/api/v1/instruments")
class InstrumentController {

    private final MatchingService service;

    InstrumentController(MatchingService service) {
        this.service = service;
    }

    @GetMapping
    List<InstrumentResponse> instruments() {
        return service.instruments().stream()
                .map(i -> InstrumentResponse.of(i, service.isHalted(i.symbol())))
                .toList();
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
