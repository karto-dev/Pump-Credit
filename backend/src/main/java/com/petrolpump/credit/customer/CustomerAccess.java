package com.petrolpump.credit.customer;

import com.petrolpump.credit.security.CurrentUser;
import com.petrolpump.credit.user.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

/** Customer-level data isolation: a CUSTOMER user only ever sees their own account. */
@Component
public class CustomerAccess {

    /** Any of these lets staff look up customers (cashier needs it to pick a customer for a sale). */
    public static final List<String> STAFF_VIEW_PERMISSIONS = List.of(
            "CUSTOMER_MANAGE", "INDENT_VIEW_ALL", "CREDIT_TXN_VIEW_ALL", "PAYMENT_VIEW_ALL", "LEDGER_VIEW_ALL",
            "CREDIT_LIMIT_CHANGE");

    public boolean canViewAll(CurrentUser u) {
        return !Role.CUSTOMER.equals(u.role()) && STAFF_VIEW_PERMISSIONS.stream().anyMatch(u::has);
    }

    public void requireCanView(CurrentUser u, long customerId) {
        if (Role.CUSTOMER.equals(u.role())) {
            // 404 rather than 403 so customers cannot probe which ids exist.
            if (!Objects.equals(u.customerId(), customerId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
            }
            return;
        }
        if (!canViewAll(u)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to view customers");
        }
    }
}
