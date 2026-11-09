package io.temporal.demo.docrouting.activities;

import io.temporal.demo.docrouting.model.DocumentRequest;

/** Input to the {@code ArchiveDocument} Activity. */
public record ArchiveDocumentInput(DocumentRequest doc) {}
