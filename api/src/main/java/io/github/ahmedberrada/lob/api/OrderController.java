package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.CancelRejected;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.MassCancel;
import io.github.ahmedberrada.lob.core.MassCancelled;
import io.github.ahmedberrada.lob.core.OrderCancelled;
import io.github.ahmedberrada.lob.core.OrderRejected;
import io.github.ahmedberrada.lob.core.RejectReason;
import io.github.ahmedberrada.lob.core.ReplaceRejected;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Order entry (docs/interfaces/rest-api.md). Each handler returns the service's future, so the servlet
 * thread is released while the instrument's writer thread works (ADR-0005 §2).
 */
@RestController
@RequestMapping("/api/v1/instruments/{symbol}/orders")
class OrderController {

    /** The member an order is entered for; absent means the operator, or the caller's own member (MB-001, AC-005). */
    static final String MEMBER_HEADER = "X-Member-Id";

    private final MatchingService service;
    private final MemberResolver members;

    OrderController(MatchingService service, MemberResolver members) {
        this.service = service;
        this.members = members;
    }

    @PostMapping
    CompletableFuture<ResponseEntity<OrderResponse>> placeOrder(
            @PathVariable String symbol, @RequestHeader(name = MEMBER_HEADER, required = false) @PositiveOrZero Long memberHeader,
            Authentication authentication,
            @Valid @RequestBody OrderRequest request) {
        InstrumentConfig instrument = instrument(symbol);
        return service.submit(symbol, request.toCommand(instrument), members.resolve(authentication, memberHeader), null).thenApply(batch -> {
            if (batch.events().getFirst() instanceof OrderRejected rejected) {
                throw new OrderRejectedException(rejected.orderId(), rejected.reason());
            }
            OrderResponse response = OrderResponse.from(batch, instrument);
            URI location = URI.create("/api/v1/instruments/" + symbol + "/orders/" + response.orderId());
            return ResponseEntity.created(location).body(response);
        });
    }

    @DeleteMapping("/{orderId}")
    CompletableFuture<CancelResponse> cancelOrder(@PathVariable String symbol, @PathVariable long orderId,
            @RequestHeader(name = MEMBER_HEADER, required = false) @PositiveOrZero Long memberHeader,
            Authentication authentication) {
        return service.submit(symbol, new CancelOrder(orderId), members.resolve(authentication, memberHeader), null).thenApply(batch -> {
            Event event = batch.events().getFirst();
            if (event instanceof CancelRejected) {
                throw new OrderNotRestingException(orderId);
            }
            OrderCancelled cancelled = (OrderCancelled) event;
            return new CancelResponse(orderId, cancelled.cancelledQuantity(), batch.commandSequence(),
                    Timestamps.of(batch));
        });
    }

    /**
     * Cancels all the caller's open orders in the instrument, on one side or both (RK-006). The operator (an
     * {@code ADMIN} naming no member) cancels every member's orders, and must say so with {@code allMembers=true}:
     * a forgotten header must not empty the book.
     */
    @DeleteMapping
    CompletableFuture<MassCancelResponse> massCancel(@PathVariable String symbol,
            @RequestParam(required = false) String side, @RequestParam(defaultValue = "false") boolean allMembers,
            @RequestHeader(name = MEMBER_HEADER, required = false) @PositiveOrZero Long memberHeader,
            Authentication authentication) {
        Side parsed;
        try {
            parsed = side == null ? null : Side.valueOf(side);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "side must be BUY or SELL");
        }
        long member = members.resolve(authentication, memberHeader);
        if ((member == 0) != allMembers) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, member == 0
                    ? "a mass cancel by the operator cancels every member's orders: name a member, or pass allMembers=true"
                    : "allMembers=true is for the operator only");
        }
        return service.submit(symbol, new MassCancel(parsed), member, null)
                .thenApply(batch -> {
                    MassCancelled summary = (MassCancelled) batch.events().getFirst();
                    List<Long> ids = batch.events().stream().filter(OrderCancelled.class::isInstance)
                            .map(e -> ((OrderCancelled) e).orderId()).toList();
                    return new MassCancelResponse(symbol, summary.side(), summary.cancelledOrders(), ids,
                            batch.commandSequence(), Timestamps.of(batch));
                });
    }

    @PutMapping("/{orderId}")
    CompletableFuture<ReplaceResponse> replaceOrder(@PathVariable String symbol, @PathVariable long orderId,
            @RequestHeader(name = MEMBER_HEADER, required = false) @PositiveOrZero Long memberHeader,
            Authentication authentication,
            @Valid @RequestBody ReplaceRequest request) {
        InstrumentConfig instrument = instrument(symbol);
        return service.submit(symbol, request.toCommand(orderId, instrument), members.resolve(authentication, memberHeader), null).thenApply(batch -> {
            if (batch.events().getFirst() instanceof ReplaceRejected rejected) {
                if (rejected.reason() == RejectReason.UNKNOWN_ORDER) {
                    throw new OrderNotRestingException(orderId);
                }
                throw new ReplaceRejectedException(orderId, rejected.reason());
            }
            return ReplaceResponse.from(batch, instrument);
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
