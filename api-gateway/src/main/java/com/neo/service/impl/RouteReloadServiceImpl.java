package com.neo.service.impl;

import com.neo.service.RouteReloadService;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class RouteReloadServiceImpl implements RouteReloadService {

  private final ApplicationEventPublisher applicationEventPublisher;

  public RouteReloadServiceImpl(ApplicationEventPublisher applicationEventPublisher) {
    this.applicationEventPublisher = applicationEventPublisher;
  }

  @Override
  public void reloadRoutes() {
    applicationEventPublisher.publishEvent(new RefreshRoutesEvent(this));
  }
}
