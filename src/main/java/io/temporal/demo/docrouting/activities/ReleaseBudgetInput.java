package io.temporal.demo.docrouting.activities;

import io.temporal.demo.docrouting.model.DocumentRequest;

/** Input to the {@code ReleaseBudget} Activity. */
public record ReleaseBudgetInput(DocumentRequest doc) {}
