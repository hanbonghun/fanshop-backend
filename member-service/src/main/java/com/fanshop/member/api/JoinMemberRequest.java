package com.fanshop.member.api;

import com.fanshop.member.domain.Member;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class JoinMemberRequest {

    private String email;

    private String name;

    private String address;

    private String password;

    public Member toEntity(String encodedPassword) {
        return new Member(email, name, address, encodedPassword);
    }

}
