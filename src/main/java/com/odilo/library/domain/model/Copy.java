package com.odilo.library.domain.model;

import com.odilo.library.domain.exception.DomainException;
import java.util.Objects;

public final class Copy {

    private final CopyId id;
    private final TitleId titleId;
    private volatile CopyStatus status;

    public Copy(CopyId id, TitleId titleId) {
        this.id = Objects.requireNonNull(id, "copy ID cannot be null");
        this.titleId = Objects.requireNonNull(titleId, "title ID cannot be null");
        this.status = CopyStatus.AVAILABLE; //inicializamos siempre como disponible una nueva copia
    }

    public CopyId id() {
        return id;
    }

    public TitleId titleId() {
        return titleId;
    }

    public CopyStatus status() {
        return status;
    }

    public synchronized void markOnLoan() {
        transition(CopyStatus.AVAILABLE, CopyStatus.ON_LOAN);
    }

    public synchronized void markAvailableFromLoan() {
        transition(CopyStatus.ON_LOAN, CopyStatus.AVAILABLE);
    }

    public synchronized void markHeld() {
        transition(CopyStatus.ON_LOAN, CopyStatus.HELD);
    }

    public synchronized void markAvailableFromHold() {
        transition(CopyStatus.HELD, CopyStatus.AVAILABLE);
    }

    public synchronized void markOnLoanFromHold() {
        transition(CopyStatus.HELD, CopyStatus.ON_LOAN);
    }

    private void transition(CopyStatus expected, CopyStatus next) {
        if (status != expected) {
            throw new DomainException("copy must be " + expected + " to become " + next);
        }
        status = next;
    }
}
