package com.neo.mapper;

import com.neo.dto.RouteRequest;
import com.neo.dto.RouteResponse;
import com.neo.entity.ApiRoute;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RouteMapper {

  RouteResponse toRouteResponse(ApiRoute apiRoute);

  ApiRoute toRoute(RouteRequest routeRequest);
}
