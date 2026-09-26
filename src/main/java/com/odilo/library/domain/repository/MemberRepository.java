package com.odilo.library.domain.repository;

import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.model.Member;

import java.util.Optional;

public interface MemberRepository {

    Optional<Member> findById(MemberId id);

    void save(Member member);
}
