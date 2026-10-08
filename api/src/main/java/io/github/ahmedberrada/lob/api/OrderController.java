package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.CancelRejected;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.OrderCancelled;
import io.github.ahmedberrada.lob.core.OrderRejected;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order entry (docs/interfaces/rest-api.md). Each handler returns the service's future, so the servlet
 * thread is released while the instrument's writer thread works (ADR-0005 §2).
 */
@RestController
@RequestMapping("/api/v1/instruments/{symbol}/orders")
class OrderController {

    private final MatchingService service;

    OrderController(MatchingService service) {
        this.service = service;
    }

    @PostMapping
    CompletableFuture<ResponseEntity<OrderResponse>> placeOrder(
            @PathVariable String symbol, @Valid @RequestBody OrderRequest request) {
        InstrumentConfig instrument = instrument(symbol);
        return service.submit(symbol, request.toCommand(instrument)).thenApply(batch -> {
            if (batch.events().getFirst() instanceof OrderRejected rejected) {
                throw new OrderRejectedException(rejected.orderId(), rejected.reason());
            }
            OrderResponse response = OrderResponse.from(batch, instrument);
            URI location = URI.create("/api/v1/instruments/" + symbol + "/orders/" + response.orderId());
            return ResponseEntity.created(location).body(response);
        });
    }

    @DeleteMapping("/{orderId}")
    CompletableFuture<CancelResponse> cancelOrder(@PathVariable String symbol, @PathVariable long orderId) {
        return service.submit(symbol, new CancelOrder(orderId)).thenApply(batch -> {
            Event event = batch.events().getFirst();
            if (event instanceof CancelRejected) {
                throw new OrderNotRestingException(orderId);
            }
            OrderCancelled cancelled = (OrderCancelled) event;
            return new CancelResponse(orderId, cancelled.cancelledQuantity(), batch.commandSequence(),
                    Timestamps.of(batch));
        });
    }

    @GetMapping("/{orderId}")
    CompletableFuture<OrderStatusResponse> orderStatus(@PathVariable String symbol, @PathVariable long orderId) {
        return service.restingQuantity(symbol, orderId)
                .thenApply(resting -> new OrderStatusResponse(orderId, resting > 0, resting));
    }

    private InstrumentConfig instrument(String symbol) {
        return service.instrument(symbol).orElseThrow(() ->
                new RequestRefusedException(RequestRefusedException.Reason.UNKNOWN_INSTRUMENT, symbol));
    }
}
