package com.odilo.library.infrastructure.memory;

import com.odilo.library.domain.model.Title;
import com.odilo.library.domain.model.TitleId;
import com.odilo.library.domain.repository.TitleRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryTitleRepository implements TitleRepository {

    private final ConcurrentMap<TitleId, Title> titles = new ConcurrentHashMap<>();

    @Override
    public Optional<Title> findById(TitleId id) {
        return Optional.ofNullable(titles.get(
                Objects.requireNonNull(id, "title ID cannot be null")));
    }

    @Override
    public void save(Title title) {
        Objects.requireNonNull(title, "title cannot be null");
        titles.put(title.id(), title);
    }
}
