package io.temporal.demo.docrouting.workflow;

/** The ERP's asynchronous confirmation of an invoice posting, delivered by callback and passed in as a Signal. */
public record ErpConfirmation(String postingId, String confirmedAt) {}
