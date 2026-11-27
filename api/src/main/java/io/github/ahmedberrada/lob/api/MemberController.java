package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.MemberCommandRejected;
import io.github.ahmedberrada.lob.core.MemberLimits;
import io.github.ahmedberrada.lob.core.MemberState;
import io.github.ahmedberrada.lob.core.MemberSuspended;
import io.github.ahmedberrada.lob.core.ResumeMember;
import io.github.ahmedberrada.lob.core.SetMemberLimits;
import io.github.ahmedberrada.lob.core.SuspendMember;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Members' limits and the kill switch, across every instrument (docs/interfaces/rest-api.md). They need the
 * ADMIN role (AC-004).
 */
@RestController
@RequestMapping("/api/v1/members/{memberId}")
class MemberController {

    private final MatchingService service;

    MemberController(MatchingService service) {
        this.service = service;
    }

    @GetMapping
    CompletableFuture<MemberResponse> member(@PathVariable @Positive long memberId) {
        return states(memberId);
    }

    @PutMapping("/limits")
    CompletableFuture<MemberResponse> setLimits(@PathVariable @Positive long memberId,
            @Valid @RequestBody LimitsRequest request) {
        List<CompletableFuture<?>> commands = service.instruments().stream().map(instrument ->
                service.submit(instrument.symbol(), new SetMemberLimits(memberId, request.toLimits(instrument)))
                        .thenAccept(batch -> reject(batch.events().getFirst()))).collect(java.util.stream.Collectors.toList());
        return CompletableFuture.allOf(commands.toArray(CompletableFuture[]::new)).thenCompose(done -> states(memberId));
    }

    /** The kill switch (RK-004). */
    @PutMapping("/suspension")
    CompletableFuture<SuspensionResponse> suspend(@PathVariable @Positive long memberId) {
        List<CompletableFuture<Long>> commands = service.instruments().stream().map(instrument ->
                service.submit(instrument.symbol(), new SuspendMember(memberId)).thenApply(batch -> {
                    Event first = batch.events().getFirst();
                    reject(first);
                    return ((MemberSuspended) first).cancelledOrders();
                })).toList();
        return CompletableFuture.allOf(commands.toArray(CompletableFuture[]::new)).thenApply(done ->
                new SuspensionResponse(memberId, true, commands.stream().mapToLong(CompletableFuture::join).sum()));
    }

    @DeleteMapping("/suspension")
    CompletableFuture<SuspensionResponse> resume(@PathVariable @Positive long memberId) {
        List<CompletableFuture<?>> commands = service.instruments().stream().map(instrument ->
                service.submit(instrument.symbol(), new ResumeMember(memberId))
                        .thenAccept(batch -> reject(batch.events().getFirst())))
                .collect(java.util.stream.Collectors.toList());
        return CompletableFuture.allOf(commands.toArray(CompletableFuture[]::new))
                .thenApply(done -> new SuspensionResponse(memberId, false, 0));
    }

    private static void reject(Event first) {
        if (first instanceof MemberCommandRejected rejected) {
            throw new MemberCommandRejectedException(rejected.memberId(), rejected.reason());
        }
    }

    private CompletableFuture<MemberResponse> states(long memberId) {
        List<InstrumentConfig> instruments = service.instruments();
        List<CompletableFuture<MemberResponse.InstrumentState>> futures = instruments.stream().map(instrument ->
                service.state(instrument.symbol()).thenApply(state -> MemberResponse.InstrumentState.of(instrument,
                        state.engine().members().stream().filter(m -> m.memberId() == memberId).findFirst()
                                .orElse(new MemberState(memberId, MemberLimits.NONE, false, 0))))).toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(done ->
                new MemberResponse(memberId, futures.stream().map(CompletableFuture::join).toList()));
    }
}
