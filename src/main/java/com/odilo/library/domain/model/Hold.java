package com.odilo.library.domain.model;

import com.odilo.library.domain.exception.DomainException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class Hold {

    private final HoldId id;
    private final MemberId memberId;
    private final TitleId titleId;
    private final Instant createdAt;
    private HoldStatus status;
    private CopyId assignedCopyId;
    private Instant assignedAt;
    private Instant expiresAt;

    public Hold(HoldId id, MemberId memberId, TitleId titleId, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "hold ID cannot be null");
        this.memberId = Objects.requireNonNull(memberId, "member ID cannot be null");
        this.titleId = Objects.requireNonNull(titleId, "title ID cannot be null");
        this.createdAt = Objects.requireNonNull(createdAt, "creation time cannot be null");
        this.status = HoldStatus.WAITING; //comienza en estado de espera
    }

    public HoldId id() {
        return id;
    }

    public MemberId memberId() {
        return memberId;
    }

    public TitleId titleId() {
        return titleId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public HoldStatus status() {
        return status;
    }

    public Optional<CopyId> assignedCopyId() {
        return Optional.ofNullable(assignedCopyId);
    }

    public Optional<Instant> assignedAt() {
        return Optional.ofNullable(assignedAt);
    }

    public Optional<Instant> expiresAt() {
        return Optional.ofNullable(expiresAt);
    }


    //asigna una copia a una reserva, la reserva debe estar en espera y actualiza a estado asignada
    public void assignCopy(CopyId copyId, Instant at, Instant expiration) {
        Objects.requireNonNull(copyId, "assigned copy ID cannot be null");
        Objects.requireNonNull(at, "assignment time cannot be null");
        Objects.requireNonNull(expiration, "expiration time cannot be null");
        if (status != HoldStatus.WAITING) {
            throw new DomainException("only a waiting hold can be assigned a copy");
        }
        if (at.isBefore(createdAt) || !expiration.isAfter(at)) {
            throw new IllegalArgumentException("assignment and expiration times are invalid");
        }
        assignedCopyId = copyId;
        assignedAt = at;
        expiresAt = expiration;
        status = HoldStatus.ASSIGNED;
    }

    // registra cuando un miembro recoge una copia asignada, solo si estado es asignado
    public void collectAt(Instant at) {
        Objects.requireNonNull(at, "collection time cannot be null");
        if (status != HoldStatus.ASSIGNED) {
            throw new DomainException("only an assigned hold can be collected");
        }
        if (at.isBefore(assignedAt)) {
            throw new IllegalArgumentException("collection time cannot precede assignment");
        }
        if (!at.isBefore(expiresAt)) {
            throw new DomainException("hold has expired");
        }
        status = HoldStatus.COLLECTED;
    }

    // marca la reserva como expirada, solo si estado es asignado y la fecha de expiracion es anterior a la fecha actual
    public void expireAt(Instant at) {
        Objects.requireNonNull(at, "expiration check time cannot be null");
        if (status != HoldStatus.ASSIGNED || at.isBefore(expiresAt)) {
            throw new DomainException("hold cannot expire yet");
        }
        status = HoldStatus.EXPIRED;
    }

    //cancelar anticipadanete una reserva asignadaasignada
    public void forfeitAt(Instant at) {
        Objects.requireNonNull(at, "forfeit time cannot be null");
        if (status != HoldStatus.ASSIGNED) {
            throw new DomainException("only an assigned hold can be forfeited");
        }
        if (at.isBefore(assignedAt) || !at.isBefore(expiresAt)) {
            throw new IllegalArgumentException("forfeit time must be within the pickup window");
        }
        status = HoldStatus.EXPIRED;
    }
}
