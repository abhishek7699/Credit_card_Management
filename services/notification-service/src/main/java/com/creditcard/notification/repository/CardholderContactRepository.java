package com.creditcard.notification.repository;

import com.creditcard.notification.domain.CardholderContact;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardholderContactRepository extends JpaRepository<CardholderContact, String> {
}
