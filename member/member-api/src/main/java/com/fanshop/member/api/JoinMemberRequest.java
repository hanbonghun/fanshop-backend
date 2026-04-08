package com.fanshop.member.api;

import com.fanshop.member.domain.Member;

public class JoinMemberRequest {

	private String email;

	private String name;

	private String address;

	protected JoinMemberRequest() {
	}

	public JoinMemberRequest(String email, String name, String address) {
		this.email = email;
		this.name = name;
		this.address = address;
	}

	public String getEmail() {
		return email;
	}

	public String getName() {
		return name;
	}

	public String getAddress() {
		return address;
	}

	public Member toEntity() {
		return new Member(email, name, address);
	}

}
