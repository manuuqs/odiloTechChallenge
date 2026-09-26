package com.odilo.library.infrastructure.memory;

import com.odilo.library.domain.model.Hold;
import com.odilo.library.domain.model.HoldId;
import com.odilo.library.domain.model.HoldStatus;
import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.model.TitleId;
import com.odilo.library.domain.repository.HoldRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryHoldRepository implements HoldRepository {

    private final ConcurrentMap<HoldId, Hold> holds = new ConcurrentHashMap<>();

    @Override
    public Optional<Hold> findById(HoldId id) {
        return Optional.ofNullable(holds.get(
                Objects.requireNonNull(id, "hold ID cannot be null")));
    }

    @Override
    public List<Hold> findActiveByTitleId(TitleId titleId) {
        Objects.requireNonNull(titleId, "title ID cannot be null");

        return holds.values()
                .stream()
                .filter(hold -> hold.titleId().equals(titleId))
                .filter(this::isActive)
                .sorted(byCreationTime())
                .toList();
    }

    @Override
    public List<Hold> findWaitingByTitleId(TitleId titleId) {
        Objects.requireNonNull(titleId, "title ID cannot be null");

        return holds.values()
                .stream()
                .filter(hold -> hold.titleId().equals(titleId))
                .filter(hold -> hold.status() == HoldStatus.WAITING)
                .sorted(byCreationTime())
                .toList();
    }

    @Override
    public boolean existsActiveByMemberAndTitle(MemberId memberId, TitleId titleId) {
        Objects.requireNonNull(memberId, "member ID cannot be null");
        Objects.requireNonNull(titleId, "title ID cannot be null");

        return holds.values()
                .stream()
                .anyMatch(hold ->
                        hold.memberId().equals(memberId)
                                && hold.titleId().equals(titleId)
                                && isActive(hold));
    }

    @Override
    public void save(Hold hold) {
        Objects.requireNonNull(hold, "hold cannot be null");
        holds.put(hold.id(), hold);
    }

    private boolean isActive(Hold hold) {
        return hold.status() == HoldStatus.WAITING
                || hold.status() == HoldStatus.ASSIGNED;
    }

    private Comparator<Hold> byCreationTime() {
        return Comparator
                .comparing(Hold::createdAt)
                .thenComparing(hold -> hold.id().value());
    }
}