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
        if (status != CopyStatus.AVAILABLE) {
            throw new DomainException("copy is not available for loan");
        }
        status = CopyStatus.ON_LOAN;
    }
}
