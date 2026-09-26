package com.odilo.library.infrastructure.memory;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.odilo.library.domain.model.Member;
import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.model.Tier;
import com.odilo.library.domain.repository.MemberRepository;
import org.junit.jupiter.api.Test;

class InMemoryMemberRepositoryTest {

    @Test
    void savesFindsAndReplacesMemberById() {
        MemberRepository members = new InMemoryMemberRepository();
        MemberId id = new MemberId("member-1");
        assertTrue(members.findById(id).isEmpty());

        members.save(new Member(id, "Alex", Tier.STANDARD));
        Member updated = new Member(id, "Alex", Tier.STUDENT);
        members.save(updated);

        assertSame(updated, members.findById(id).orElseThrow());
    }

    @Test
    void rejectsNullInputs() {
        MemberRepository members = new InMemoryMemberRepository();
        assertThrows(NullPointerException.class, () -> members.findById(null));
        assertThrows(NullPointerException.class, () -> members.save(null));
    }
}
