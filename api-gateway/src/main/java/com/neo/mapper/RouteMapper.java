package com.neo.mapper;

import com.neo.dto.RouteRequest;
import com.neo.dto.RouteResponse;
import com.neo.entity.ApiRoute;
import com.neo.security.RoutePermissions;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RouteMapper {

  RouteResponse toRouteResponse(ApiRoute apiRoute);

  ApiRoute toRoute(RouteRequest routeRequest);

  default String permissionsToColumn(List<String> permissions) {
    return RoutePermissions.join(permissions);
  }

  default List<String> columnToPermissions(String permissions) {
    return List.copyOf(RoutePermissions.parse(permissions));
  }
}
