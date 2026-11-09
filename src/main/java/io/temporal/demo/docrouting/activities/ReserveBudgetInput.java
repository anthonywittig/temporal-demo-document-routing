package io.temporal.demo.docrouting.activities;

import io.temporal.demo.docrouting.model.DocumentRequest;

/** Input to the {@code ReserveBudget} Activity. */
public record ReserveBudgetInput(DocumentRequest doc) {}
