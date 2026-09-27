package com.odilo.library.domain.model;

import com.odilo.library.domain.exception.DomainException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class Loan implements Action{

    private final LoanId id;
    private final MemberId memberId;
    private final CopyId copyId;
    private final Instant startedAt;
    private Instant dueAt;
    private int renewalCount;
    private Instant returnedAt;

    public Loan(LoanId id, MemberId memberId, CopyId copyId, Instant startedAt, Instant dueAt) {
        this.id = Objects.requireNonNull(id, "loan ID cannot be null");
        this.memberId = Objects.requireNonNull(memberId, "member ID cannot be null");
        this.copyId = Objects.requireNonNull(copyId, "copy ID cannot be null");
        this.startedAt = Objects.requireNonNull(startedAt, "start time cannot be null");
        this.dueAt = Objects.requireNonNull(dueAt, "due time cannot be null");
        if (!dueAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("due time must be after start time");
        }
    }

    public LoanId id() {
        return id;
    }

    public MemberId memberId() {
        return memberId;
    }

    public CopyId copyId() {
        return copyId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant dueAt() {
        return dueAt;
    }

    public int renewalCount() {
        return renewalCount;
    }

    public Optional<Instant> returnedAt() {
        return Optional.ofNullable(returnedAt);
    }

    public void markReturned(Instant at) {
        Objects.requireNonNull(at, "return time cannot be null");
        if (returnedAt != null) {
            throw new DomainException("loan has already been returned");
        }
        if (at.isBefore(startedAt)) {
            throw new IllegalArgumentException("return time cannot precede start time");
        }
        returnedAt = at;
    }

    public void renewUntil(Instant newDueAt) {
        Objects.requireNonNull(newDueAt, "new due time cannot be null");
        if (returnedAt != null) {
            throw new DomainException("returned loan cannot be renewed");
        }
        if (!newDueAt.isAfter(dueAt)) {
            throw new IllegalArgumentException("new due time must be after current due time");
        }
        dueAt = newDueAt;
        renewalCount++;
    }

    @Override
    public boolean isLoan() {
        return true;
    }

    @Override
    public boolean isHold() {
        return false;
    }
}
