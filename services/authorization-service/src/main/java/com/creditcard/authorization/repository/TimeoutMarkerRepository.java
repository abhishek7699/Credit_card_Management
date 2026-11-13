package com.creditcard.authorization.repository;

import com.creditcard.authorization.domain.TimeoutMarker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TimeoutMarkerRepository extends JpaRepository<TimeoutMarker, UUID> {
}
