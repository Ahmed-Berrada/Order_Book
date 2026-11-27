package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.MemberLimits;
import io.github.ahmedberrada.lob.core.MemberState;
import io.github.ahmedberrada.lob.core.SetMemberLimits;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import org.springframework.stereotype.Component;

/**
 * Brings every instrument's member limits to what the configuration says when the venue starts (RK-001).
 * Limits are journaled commands, so a restart with the same configuration journals nothing.
 */
@Component
class MemberLimitsApplier {

    MemberLimitsApplier(MatchingService service, LobProperties properties) {
        for (InstrumentConfig instrument : service.instruments()) {
            var state = service.state(instrument.symbol()).join().engine();
            for (LobProperties.MemberProperties member : properties.members()) {
                MemberLimits wanted = member.limits() == null ? MemberLimits.NONE : member.limits().toLimits(instrument);
                MemberLimits current = state.members().stream().filter(m -> m.memberId() == member.id())
                        .map(MemberState::limits).findFirst().orElse(MemberLimits.NONE);
                if (!wanted.equals(current)) {
                    service.submit(instrument.symbol(), new SetMemberLimits(member.id(), wanted)).join();
                }
            }
        }
    }
}
