package com.neo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "webclient.timeout")
public class WebClientProperties {
  private int connect;
  private int response;
  private int read;
  private int write;

  public int getConnect() {
    return connect;
  }

  public int getResponse() {
    return response;
  }

  public int getRead() {
    return read;
  }

  public int getWrite() {
    return write;
  }
}
