package com.petrolpump.credit.customer;

import com.petrolpump.credit.customer.CustomerDtos.CreateCustomerRequest;
import com.petrolpump.credit.customer.CustomerDtos.CustomerDetail;
import com.petrolpump.credit.customer.CustomerDtos.CustomerRow;
import com.petrolpump.credit.customer.CustomerDtos.StatusChangeRequest;
import com.petrolpump.credit.customer.CustomerDtos.UpdateCustomerRequest;
import com.petrolpump.credit.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customers;
    private final CustomerAccess access;

    public CustomerController(CustomerService customers, CustomerAccess access) {
        this.customers = customers;
        this.access = access;
    }

    @GetMapping
    public List<CustomerRow> search(@AuthenticationPrincipal CurrentUser user,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "false") boolean overLimit) {
        if (!access.canViewAll(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to view customers");
        }
        return customers.search(q, status, overLimit);
    }

    @GetMapping("/{id}")
    public CustomerDetail get(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        access.requireCanView(user, id);
        return customers.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public CustomerDetail create(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CreateCustomerRequest req) {
        return customers.create(req, user);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public CustomerDetail update(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                 @Valid @RequestBody UpdateCustomerRequest req) {
        return customers.update(id, req, user);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public CustomerDetail changeStatus(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                       @Valid @RequestBody StatusChangeRequest req) {
        return customers.changeStatus(id, req, user);
    }
}
