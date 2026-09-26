package com.odilo.library.infrastructure.memory;

import com.odilo.library.domain.model.Member;
import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.repository.MemberRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryMemberRepository implements MemberRepository {

    private final ConcurrentMap<MemberId, Member> members = new ConcurrentHashMap<>();

    @Override
    public Optional<Member> findById(MemberId id) {
        return Optional.ofNullable(members.get(
                Objects.requireNonNull(id, "member ID cannot be null")));
    }

    @Override
    public void save(Member member) {
        Objects.requireNonNull(member, "member cannot be null");
        members.put(member.id(), member);
    }
}