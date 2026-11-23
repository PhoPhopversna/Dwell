package com.neo.mapper;

import com.neo.dto.RouteRequest;
import com.neo.dto.RouteResponse;
import com.neo.entity.ApiRoute;
import com.neo.security.RouteRoles;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RouteMapper {

  RouteResponse toRouteResponse(ApiRoute apiRoute);

  ApiRoute toRoute(RouteRequest routeRequest);

  default String rolesToColumn(List<String> roles) {
    return RouteRoles.join(roles);
  }

  default List<String> columnToRoles(String roles) {
    return List.copyOf(RouteRoles.parse(roles));
  }
}
