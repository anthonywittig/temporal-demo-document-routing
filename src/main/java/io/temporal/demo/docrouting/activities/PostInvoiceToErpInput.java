package io.temporal.demo.docrouting.activities;

import io.temporal.demo.docrouting.model.DocumentRequest;

/** Input to the {@code PostInvoiceToErp} Activity. */
public record PostInvoiceToErpInput(DocumentRequest doc) {}
