package io.temporal.demo.docrouting.workflow;

/** Input to the {@code NotifyRequester} Activity: the outcome to tell the requester about. */
public record NotifyRequesterInput(DocumentRequest doc, String message) {}
