package com.odilo.library.domain.repository;

import com.odilo.library.domain.model.Title;
import com.odilo.library.domain.model.TitleId;

import java.util.Optional;

public interface TitleRepository {

    Optional<Title> findById(TitleId id);

    void save(Title title);
}
