package io.temporal.demo.docrouting.activities;

import io.temporal.demo.docrouting.model.DocumentRequest;

/** Input to the {@code Classify} Activity. */
public record ClassifyInput(DocumentRequest doc) {}
