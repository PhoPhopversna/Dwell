package com.neo.event;

import java.util.UUID;

public record ProductImageUploadedEvent(UUID productImageId, UUID productId, String objectName) {}
