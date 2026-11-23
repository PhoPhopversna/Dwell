package com.neo.event;

import java.util.UUID;

public record ProductImageDeletedEvent(
    UUID productImageId, UUID productId, String objectName, String thumbnailObjectName) {}
