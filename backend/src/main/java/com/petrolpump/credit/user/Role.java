package com.petrolpump.credit.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "roles")
public class Role {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String CASHIER = "CASHIER";
    public static final String MANAGER = "MANAGER";
    public static final String ADMIN = "ADMIN";

    @Id
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
}
