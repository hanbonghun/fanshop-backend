package com.fanshop.member.domain;

import com.fanshop.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "members")
public class Member extends BaseEntity {

	@Column(nullable = false, unique = true)
	private String email;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String address;

	@Column(nullable = false)
	private String password;

	public Member(String email, String name, String address, String password) {
		this.email = email;
		this.name = name;
		this.address = address;
		this.password = password;
	}

	public void updateEmail(String email) {
		this.email = email;
	}

	public void updateName(String name) {
		this.name = name;
	}

	public void updateAddress(String address) {
		this.address = address;
	}

}
