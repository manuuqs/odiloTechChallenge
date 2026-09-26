package com.odilo.library.domain.repository;

import com.odilo.library.domain.model.Copy;
import com.odilo.library.domain.model.CopyId;
import com.odilo.library.domain.model.TitleId;

import java.util.List;
import java.util.Optional;

public interface CopyRepository {

    Optional<Copy> findById(CopyId id);

    List<Copy> findByTitleId(TitleId titleId);

    List<Copy> findAvailableByTitleId(TitleId titleId);

    void save(Copy copy);
}
