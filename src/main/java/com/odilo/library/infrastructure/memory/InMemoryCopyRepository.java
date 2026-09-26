package com.odilo.library.infrastructure.memory;

import com.odilo.library.domain.model.Copy;
import com.odilo.library.domain.model.CopyId;
import com.odilo.library.domain.model.CopyStatus;
import com.odilo.library.domain.model.TitleId;
import com.odilo.library.domain.repository.CopyRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryCopyRepository implements CopyRepository {

    private final ConcurrentMap<CopyId, Copy> copies = new ConcurrentHashMap<>();

    @Override
    public Optional<Copy> findById(CopyId id) {
        return Optional.ofNullable(copies.get(
                Objects.requireNonNull(id, "copy ID cannot be null")));
    }

    @Override
    public List<Copy> findByTitleId(TitleId titleId) {
        Objects.requireNonNull(titleId, "title ID cannot be null");

        return copies.values()
                .stream()
                .filter(copy -> copy.titleId().equals(titleId))
                .toList();
    }

    @Override
    public List<Copy> findAvailableByTitleId(TitleId titleId) {
        return findByTitleId(titleId)
                .stream()
                .filter(copy -> copy.status() == CopyStatus.AVAILABLE)
                .toList();
    }

    @Override
    public void save(Copy copy) {
        Objects.requireNonNull(copy, "copy cannot be null");
        copies.put(copy.id(), copy);
    }
}
