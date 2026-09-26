package com.odilo.library.domain.repository;

import com.odilo.library.domain.model.Hold;
import com.odilo.library.domain.model.HoldId;
import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.model.TitleId;

import java.util.List;
import java.util.Optional;

public interface HoldRepository {

    Optional<Hold> findById(HoldId id);

    List<Hold> findActiveByTitleId(TitleId titleId);

    List<Hold> findWaitingByTitleId(TitleId titleId);

    boolean existsActiveByMemberAndTitle(MemberId memberId, TitleId titleId);

    void save(Hold hold);
}
